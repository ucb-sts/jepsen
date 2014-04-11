(ns jepsen.ddmin
  (:require [jepsen.failure :as failure]
            [jepsen.util :as util]
            [jepsen.console :as console])
  (:use jepsen.load))

; ----- Invariants -----
;  TODO(cs): factor these out into a separate file
;  TODO(cs): probably need specific failure signatures rather than general
;            invariant failures
(defn all-writes-succeed [target acked results]
  (= (set target) (set results)))

(defn all-acked-writes-succeed [target acked results]
  (= (set acked) (set results)))

; ----- delta debugging functions -----

; TODO(cs): redundant with set-app/filter-acked, but adding a dependency on
; set-app introduces a circular dependency.
(defn filter-acked [log]
  (->> log
    (remove nil?)
    (remove #(= :error (:state %)))
    (map :req)))

(defn replay-worker
  [add min-epoch app log-chunk]
  (future
    (let [emit (->> app
                   (partial add)
                   wrap-catch
                   wrap-timestamp
                   wrap-latency
                   wrap-record-req
                   console/wrap-ordered-log)
          result (atom [])
          last-timestamp-ms (atom min-epoch)
          reqs (atom log-chunk)]
      (while (> (count @reqs) 0)
        (let [req (first @reqs)
              sleep-ms (- (:start_epoch req) @last-timestamp-ms)]
          ; TODO(cs): account for the time it takes to emit?
          (util/sleep sleep-ms)
          (reset! last-timestamp-ms (:start_epoch req))
          (swap! result conj (emit (:req req)))
          (swap! reqs rest)
        ))
     @result)
   ))

(defn create-workers [add log]
  ; log has the form ({:req 0, :latency 31.387917, :app app}...)
  ; partition into a list of replay-workers, one for each app.
  (let [extract-app (fn [lst] (:app (first lst)))
        log-chunks (partition-by (comp str :app) (sort-by (comp str :app) log))
        apps (map extract-app log-chunks)
        min-epoch (apply min (map :start_epoch log))]
    (map (partial replay-worker add min-epoch) apps log-chunks)
  ))

(defn replay [invariant
              init-apps
              apps
              nodes
              failure-mode
              failure_delay_seconds
              recovery_delay_seconds
              get-results
              teardown
              add
              log]
  (init-apps apps)

  (let [; All keys we tried to write
        target (set (map :req log))
        _ (util/ordered-println "Replaying" target)
        witch (failure/schedule! failure-mode
                                 nodes
                                 failure_delay_seconds
                                 recovery_delay_seconds)
        new-log (->> (create-workers add log)
                     doall
                     (mapcat deref)
                     (sort-by :req))
        _ (util/ordered-println "New LOG" new-log)
        acked (filter-acked new-log)
        _ (util/ordered-println "ACKED" acked)]

    ; Wait for recovery to complete
    (util/ordered-println "@Witch" @witch)
    (let [results (get-results (first apps))]
      ; Shut down apps
      (util/ordered-println "Results" results)
      (dorun (map teardown apps))
      ; Check and return result of invariant
      (invariant target acked results)
    )))

(defn splitlog [log]
  (split-at (int (/ (count log) 2)) log))

(defn joinlogs [l1 l2]
  (sort-by :start_epoch (concat l1 l2)))

(defn ddmin2 [ddtest log remainder]
  "Precondition: (not (test log)), i.e. log produces an invariant violation"
  (if (= (count log) 1)
    ; Base case
    (do
      (util/ordered-println "base case")
      log)
    ; Recursive cases
    (let [split (splitlog log)
          l1 (first split)
          l2 (second split)]
      (cond
        ; "in T1"
        (ddtest (joinlogs l1 remainder)) (do
          (util/ordered-println "in T1")
          (ddmin2 ddtest l1 remainder))
        ; "in T2"
        (ddtest (joinlogs l2 remainder)) (do
           (util/ordered-println "in T2")
           (ddmin2 ddtest l2 remainder))
        ; "interference"
        :else (do
           (util/ordered-println "interference")
           (joinlogs
             (ddmin2 ddtest l1 (joinlogs l2 remainder))
             (ddmin2 ddtest l2 (joinlogs l1 remainder))))
      ))))

(defn ddmin [ddtest log]
  (ddmin2 ddtest (sort-by :start_epoch log) []))
