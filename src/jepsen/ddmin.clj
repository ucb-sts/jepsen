(ns jepsen.ddmin
  (:require [jepsen.failure :as failure])
  (:use jepsen.load))

; ----- Invariants -----
;  TODO(cs): factor these out into a separate file
;  TODO(cs): probably need specific failure signatures rather than general
;            invariant failures
(defn all-writes-succeed [target acked results]
  (= target results))

(defn all-acked-writes-succeed [target acked results]
  (= acked results))

; ----- delta debugging functions -----

; TODO(cs): redundant with set-app/filter-acked, but adding a dependency on
; set-app introduces a circular dependency.
(defn filter-acked [log]
  (->> log
    (remove nil?)
    (remove #(= :error (:state %)))
    (map :req)))

(defn replay-worker
  [add app]
  (future
    (->> app
         (partial add)
         wrap-catch
         ; TODO(cs): extract timestamp, sleep N seconds
         ; 
         )))

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

  (let [witch (failure/schedule! failure-mode
                                 nodes
                                 failure_delay_seconds
                                 recovery_delay_seconds)
        ; log has the form ({:req 0, :latency 31.387917, :element 0, :id 1 :app app}...)
        ; TODO(cs): where are :element and :id added?
        new_log '()
        target (set (map :element log)) ; All keys we tried to write
        acked (filter-acked new_log)]

    ; Wait for recovery to complete
    (println @witch)
    (let [results (get-results (first apps))]
      ; Shut down apps
      (dorun (map teardown apps))
      ; Check and return result of invariant
      (invariant target acked results)
    )))

(defn splitlog [log]
  (split-at (int (/ (count log) 2))) log)

(defn joinlogs [l1 l2]
  (sort-by :start_epoch (concat l1 l2)))

(defn ddmin2 [ddtest log remainder]
  "Precondition: (not (test log)), i.e. log produces an invariant violation"
  (if (= (count log) 1)
    ; Base case
    log
    ; Recursive cases
    (let [split (splitlog log)
          l1 (first split)
          l2 (second split)]
      (cond
        ; "in T1"
        (ddtest (joinlogs l1 remainder)) (ddmin2 ddtest l1 remainder)
        ; "in T2"
        (ddtest (joinlogs l2 remainder)) (ddmin2 ddtest l2 remainder)
        ; "interference"
        :else (joinlogs
                (ddmin2 l1 (joinlogs l2 remainder))
                (ddmin2 l2 (joinlogs l1 remainder))))
      )))

(defn ddmin [ddtest log]
  (ddmin2 ddtest (sort-by :start_epoch) []))
