(ns day8.re-frame.debux.parse-opts-kw-test
  "Integration tests pinning the keyword-style `tracing/dbgn` surface
   to its emit-trace-body wiring. Each test runs an opt through
   `parse-opts` (via the outer macro) and asserts an observable
   downstream effect on the captured trace stream — confirming that
   parse-opts produced the expected key in the opts map.

   Companion to:
     - common/util_test.cljc — pins the parse itself.
     - if_option_test.clj    — kw-style `:if` regression coverage.
     - final_option_test.clj — kw-style `:final` / `:f`.

   This file fills in the remaining branches the review
   listed as uncovered through parse-opts: `:once`/`:o`, `:msg`/`:m`,
   `:verbose`/`:show-all`. Together with the unit tests, every
   non-cljs-only branch is now pinned at both layers."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame.debux.common.util :as util]
            [day8.re-frame.debux.dbgn :as dbgn :refer [dbgn]]
            [day8.re-frame.tracing :as tracing]))

(defn- with-unit-capture
  "Run `body-fn` with `send-trace!` redirected to a local atom; return
   the captured vec of trace entries (post `:form` tidy)."
  [body-fn]
  (let [traces (atom [])]
    (with-redefs [tracing/trace-enabled? true
                  util/send-trace! (fn [code-trace]
                                     (swap! traces conj
                                            (update code-trace :form
                                                    util/tidy-macroexpanded-form {})))
                  util/send-form!  (fn [_])]
      (body-fn))
    @traces))

;; ---------------------------------------------------------------------------
;; :msg / :m via parse-opts — every emitted :code carries the label.
;; ---------------------------------------------------------------------------

(deftest tracing-dbgn-keyword-msg-via-parse-opts
  (testing "kw-style `(tracing/dbgn form :msg \"label\")` parses to {:msg \"label\"} and labels every emission"
    (let [r      (atom nil)
          traces (with-unit-capture
                   (fn []
                     (reset! r (eval `(tracing/dbgn (-> 1 inc inc) :msg "outer")))))]
      (is (= 3 @r) "value transparency — :msg doesn't change the result")
      (is (seq traces) "at least one :code entry was emitted")
      (is (every? #(= "outer" (:msg %)) traces)
          "parse-opts mapped :msg → {:msg \"outer\"}; emit-trace-body propagated to every entry"))))

(deftest tracing-dbgn-keyword-m-alias-via-parse-opts
  (testing ":m is the shorthand alias for :msg through parse-opts"
    (let [r      (atom nil)
          traces (with-unit-capture
                   (fn []
                     (reset! r (eval `(tracing/dbgn (-> 1 inc) :m "alias")))))]
      (is (= 2 @r))
      (is (seq traces))
      (is (every? #(= "alias" (:msg %)) traces)
          ":m must consume the value-arg through nnext (same as :msg)"))))

;; ---------------------------------------------------------------------------
;; :verbose / :show-all via parse-opts — leaf literals get wrapped, so
;; the verbose run produces strictly more :code entries than the default.
;; ---------------------------------------------------------------------------

(deftest tracing-dbgn-keyword-verbose-via-parse-opts
  (testing "kw-style `(tracing/dbgn form :verbose)` wraps leaf literals → more emissions than the default"
    (let [default-traces (with-unit-capture
                           (fn [] (eval `(tracing/dbgn (+ 1 2)))))
          verbose-traces (with-unit-capture
                           (fn [] (eval `(tracing/dbgn (+ 1 2) :verbose))))]
      (is (< (count default-traces) (count verbose-traces))
          "parse-opts mapped :verbose → :verbose true; emit-trace-body wrapped the leaf 1 and 2"))))

(deftest tracing-dbgn-keyword-show-all-alias-via-parse-opts
  (testing ":show-all is the long-form alias for :verbose through parse-opts"
    (let [default-traces  (with-unit-capture
                            (fn [] (eval `(tracing/dbgn (+ 1 2)))))
          show-all-traces (with-unit-capture
                            (fn [] (eval `(tracing/dbgn (+ 1 2) :show-all))))]
      (is (< (count default-traces) (count show-all-traces))
          ":show-all must hit the {:once :o :verbose :show-all}-style aliasing branch — same effect as :verbose"))))

;; ---------------------------------------------------------------------------
;; :once / :o via parse-opts — repeated calls of the SAME macro-site
;; suppress the duplicate emission.
;;
;; `:once` keys the dedup state on `+debux-trace-id+`, which is a
;; gensym baked at macroexpansion time. Two independent `eval` calls
;; would create two different trace-ids and never dedupe — so the
;; tests below macroexpand once (inside `(fn [] ...)`) and call the
;; resulting closure twice. That mirrors the production shape: a
;; handler is reg-event-db'd once, then dispatched many times.
;; ---------------------------------------------------------------------------

(deftest tracing-dbgn-keyword-once-via-parse-opts
  (testing "kw-style `(tracing/dbgn form :once)` dedupes the second invocation of the same site"
    (util/-reset-once-state!)
    (let [traces-with-once    (atom [])
          traces-without-once (atom [])]
      (with-redefs [tracing/trace-enabled? true
                    util/send-trace! (fn [code-trace]
                                       (swap! traces-with-once conj code-trace))
                    util/send-form!  (fn [_])]
        (let [f (eval `(fn [] (tracing/dbgn (inc 1) :once)))]
          (f)
          (let [after-first (count @traces-with-once)]
            (f)
            (is (pos? after-first)
                "first call emits — :once never suppresses the first observation")
            (is (= after-first (count @traces-with-once))
                "parse-opts mapped :once → :once true; second call's identical emission was suppressed"))))
      (with-redefs [tracing/trace-enabled? true
                    util/send-trace! (fn [code-trace]
                                       (swap! traces-without-once conj code-trace))
                    util/send-form!  (fn [_])]
        (let [f (eval `(fn [] (tracing/dbgn (inc 1))))]
          (f)
          (f)
          (is (= (* 2 (count @traces-with-once))
                 (count @traces-without-once))
              "without :once, two calls emit double the traces — sanity baseline that the :once branch is what gates"))))))

(deftest tracing-dbgn-keyword-o-alias-via-parse-opts
  (testing ":o is the shorthand alias for :once through parse-opts"
    (util/-reset-once-state!)
    (let [traces (atom [])]
      (with-redefs [tracing/trace-enabled? true
                    util/send-trace! (fn [code-trace]
                                       (swap! traces conj code-trace))
                    util/send-form!  (fn [_])]
        (let [f (eval `(fn [] (tracing/dbgn (inc 1) :o)))]
          (f)
          (let [after-first (count @traces)]
            (f)
            (is (pos? after-first))
            (is (= after-first (count @traces))
                ":o must hit the {:once :o}-aliasing branch — same dedup as :once")))))))
