(ns day8.re-frame.debux.common.prod-mode-warn-test
  "Tests for the production-mode loud-fail check in
   day8.re-frame.debux.common.util. The check is
   CLJS-only (the CLJ branch of `maybe-warn-production-mode!` is a
   no-op); tests are written .cljc so the CLJ side at least exercises
   the no-op path without crashing, and the CLJS side can be run
   under shadow-cljs to verify the actual warn behaviour."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame.debux.common.util :as ut]))

;; ---------------------------------------------------------------------------
;; CLJ side — the warn fn is a no-op, but it must NOT crash on call.
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest clj-side-is-noop
     (testing "maybe-warn-production-mode! returns nil on CLJ"
       (is (nil? (#'ut/maybe-warn-production-mode!))))
     (testing "calling it through send-trace! / send-form! doesn't throw"
       ;; Both call into maybe-warn-production-mode! before doing anything
       ;; else; verify they don't crash. We're not asserting on the
       ;; downstream merge-trace! behaviour here — that's what the rest
       ;; of the test suite covers.
       (with-redefs [day8.re-frame.debux.common.util/send-trace!
                     (fn [_] :ok)
                     day8.re-frame.debux.common.util/send-form!
                     (fn [_] :ok)]
         (is (= :ok (ut/send-trace! {:form 'x :result 1
                                     :indent-level 0 :syntax-order 0 :num-seen 0})))
         (is (= :ok (ut/send-form! 'x)))))))

;; ---------------------------------------------------------------------------
;; CLJS side — exercise the warn path. Run via the shadow-cljs runtime-
;; test build (or whatever karma runner re-frame-debux uses).
;; ---------------------------------------------------------------------------

#?(:cljs
   (deftest cljs-warn-fires-once-when-goog-debug-false
     (let [warns (atom [])
           orig-warn js/console.warn]
       (set! js/console.warn (fn [& args] (swap! warns conj (apply str args))))
       (try
         ;; Simulate a release build by forcing goog.DEBUG to false.
         (let [orig-debug js/goog.DEBUG]
           (try
             (set! js/goog.DEBUG false)
             ;; Reset the warned-state so this test isn't gated by an earlier one.
             (reset! @#'ut/prod-mode-warned? false)
             (#'ut/maybe-warn-production-mode!)
             (#'ut/maybe-warn-production-mode!) ;; second call should NOT re-warn
             (is (= 1 (count @warns)) "warns exactly once per session")
             (is (re-find #"goog.DEBUG=false" (first @warns)))
             (finally
               (set! js/goog.DEBUG orig-debug))))
         (finally
           (set! js/console.warn orig-warn))))))

#?(:cljs
   (deftest cljs-warn-suppressed-when-goog-debug-true
     (let [warns (atom [])
           orig-warn js/console.warn]
       (set! js/console.warn (fn [& args] (swap! warns conj (apply str args))))
       (try
         (let [orig-debug js/goog.DEBUG]
           (try
             (set! js/goog.DEBUG true)
             (reset! @#'ut/prod-mode-warned? false)
             (#'ut/maybe-warn-production-mode!)
             (is (empty? @warns) "dev mode (goog.DEBUG true) — no warn")
             (finally
               (set! js/goog.DEBUG orig-debug))))
         (finally
           (set! js/console.warn orig-warn))))))
