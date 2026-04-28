(ns day8.re-frame.debux.common.util-test
  (:require [clojure.test :refer [deftest testing is]]
            [day8.re-frame.debux.common.util :as ut]
            [re-frame.trace :as rft]))

(deftest with-gensyms-names-test
  (testing "auto gensym patterns"
    (is (= (vals (ut/with-gensyms-names `(let [a# 1]
                                           a#)
                                        {}))
           ["a#"]))
    (is (= (vals (ut/with-gensyms-names `(let [a# 1
                                               b# 2]
                                           b#)
                                        {}))
           ["a#" "b#"])))
  (testing "anon gensym patterns"
    (is (= (vals (ut/with-gensyms-names (gensym) {}))
           ["gensym#"])))
  (testing "named gensym patterns"
    (is (= (vals (ut/with-gensyms-names (gensym "abc") {}))
           ["abc#"])))
  (testing "anon param pattern"
    (is (= (vals (ut/with-gensyms-names '#(identity %1) {}))
           ["%1"])))
  (testing "anon param pattern"
    (is (= (vals (ut/with-gensyms-names '#(%1 %2) {}))
           ["%1" "%2"])))
  (testing "symbols with numbers at the end shouldn't match named gensyms"
    (is (= (ut/with-gensyms-names '[s1234 s12345 s123456] {})
           {"s1234" "s#" "s12345" "s#" "s123456" "s#"}))))

(deftest tidy-macroexpanded-form-test
 (is (= (ut/tidy-macroexpanded-form '(let [a# 1]
                                       a#)
                                    {})
        '(let [a# 1]
           a#)))
  (is (= (ut/tidy-macroexpanded-form '#(let [a (gensym)
                                             b %2]
                                         (gensym "def"))
                                     {})
         '(fn* [%1 %2]
            (let [a (gensym)
                  b %2]
              (gensym "def")))))

  (is (= (ut/tidy-macroexpanded-form '#(inc %)
                                     {})
         '(fn* [%1] (inc %1))))
  (is (= (ut/tidy-macroexpanded-form '#(inc %1)
                                     {})
         '(fn* [%1] (inc %1)))))

#?(:clj
   (deftest send-trace-form-tidying-test
     (testing "unmarked direct send-trace! callers still get runtime form tidying"
       (with-redefs [rft/trace-enabled? true]
         (binding [rft/*current-trace* {:tags {}}]
           (ut/send-trace! {:form         '(clojure.core/inc 1)
                            :result       2
                            :indent-level 0
                            :syntax-order 1
                            :num-seen     1})
           (is (= '(inc 1)
                  (get-in rft/*current-trace* [:tags :code 0 :form]))))))
     (testing "macro-generated pre-tidied forms skip runtime tidying"
       (with-redefs [rft/trace-enabled? true
                     ut/tidy-macroexpanded-form
                     (fn [& _]
                       (throw (ex-info "tidy should not run" {})))]
         (binding [rft/*current-trace* {:tags {}}]
           (ut/send-trace! (with-meta
                             {:form         '(inc 1)
                              :result       2
                              :indent-level 0
                              :syntax-order 1
                              :num-seen     1}
                             {::ut/form-tidied? true}))
           (is (= '(inc 1)
                  (get-in rft/*current-trace* [:tags :code 0 :form]))))))))

;; ---------------------------------------------------------------------------
;; parse-opts — keyword-style opts sequence → opts map.
;;
;; `parse-opts` is the entry point for the kw-style public surface
;; `(tracing/dbgn form :once :verbose)`, `(tracing/dbgn form :msg "x")`,
;; etc. — every keyword and shorthand listed in the dbgn / fn-traced
;; docstrings flows through here. A silent rename or reorder of any
;; cond branch (the regression that motivated the :if / :condition
;; mishap) drops the option without surfacing as a test failure unless
;; the parse itself is pinned.
;;
;; Each deftest below pins exactly one cond branch; the trailing
;; mixed-input test pins that the branches don't shadow each other.
;; ---------------------------------------------------------------------------

(deftest parse-opts-empty-test
  (is (= {} (ut/parse-opts []))
      "empty opts → empty map (the :empty? branch terminates the loop)"))

(deftest parse-opts-once-keyword-test
  (testing ":once and its :o alias map to {:once true}"
    (is (= {:once true} (ut/parse-opts [:once])))
    (is (= {:once true} (ut/parse-opts [:o]))
        ":o is the documented shorthand; aliasing must survive any future cond reorder")))

(deftest parse-opts-msg-with-value-test
  (testing ":msg / :m consume the next item as the label value"
    (is (= {:msg "hello"} (ut/parse-opts [:msg "hello"])))
    (is (= {:msg "label"} (ut/parse-opts [:m "label"]))
        ":m is the shorthand alias; both must use nnext (consume value-arg)")))

(deftest parse-opts-verbose-keyword-test
  (testing ":verbose and its :show-all alias map to {:verbose true}"
    (is (= {:verbose true} (ut/parse-opts [:verbose])))
    (is (= {:verbose true} (ut/parse-opts [:show-all]))
        ":show-all is the documented long-form alias for :verbose")))

(deftest parse-opts-number-literal-test
  (testing "a number literal becomes :n (max-trace-count)"
    (is (= {:n 42} (ut/parse-opts [42])))
    (is (= {:n 0}  (ut/parse-opts [0])))
    (is (= {:n -1} (ut/parse-opts [-1]))
        "negative numbers route through the same branch — no special-casing")))

(deftest parse-opts-string-literal-test
  (testing "a bare string literal becomes :msg (label shorthand)"
    (is (= {:msg "label"} (ut/parse-opts ["label"]))
        "the kw-style sugar — `(tracing/dbgn form \"label\")` ≡ `(... :msg \"label\")`")))

(deftest parse-opts-js-keyword-test
  (testing ":js maps to {:js true}"
    (is (= {:js true} (ut/parse-opts [:js]))
        "the cljs-only flag — flag-shape, no value-arg, single :next advance")))

(deftest parse-opts-style-with-value-test
  (testing ":style / :s consume the next item as the style value"
    (is (= {:style "color: red"} (ut/parse-opts [:style "color: red"])))
    (is (= {:style {:color "blue"}} (ut/parse-opts [:s {:color "blue"}]))
        ":s is the shorthand alias; both must use nnext to read the style payload")))

(deftest parse-opts-clog-keyword-test
  (testing ":clog maps to {:clog true}"
    (is (= {:clog true} (ut/parse-opts [:clog]))
        "the console.log routing flag — flag-shape, no value-arg")))

(deftest parse-opts-mixed-test
  (testing "the cond branches don't shadow each other when several opts compose"
    (is (= {:once true :n 5 :msg "label" :verbose true}
           (ut/parse-opts [:once 5 "label" :verbose]))
        "all four branches advance the loop correctly and produce a merged map")
    (is (= {:if even? :final true :msg "x" :n 7}
           (ut/parse-opts [:if even? :final :msg "x" 7]))
        ":if (value-arg) followed by :final (flag) followed by :msg (value-arg) followed by a number (flag) — every advance shape exercised in one parse")))

#?(:clj
   (defn- with-err-str* [f]
     (let [sw (java.io.StringWriter.)]
       (binding [*err* sw]
         (let [v (f)]
           [v (str sw)])))))

#?(:clj
   (deftest parse-opts-unknown-keyword-preserves-accumulator-test
     (testing "an unrecognized option warns and is skipped, prior and later opts survive"
       (let [[result err] (with-err-str* #(ut/parse-opts [:once :unknown :final]))]
         (is (= {:once true :final true} result)
             "the cond fall-through used to return nil and drop {:once true} along with :final — :else recurs to keep both")
         (is (re-find #"unrecognized option" err)
             "the warning is emitted to *err* so the typo is visible at macroexpansion")))))

#?(:clj
   (deftest parse-opts-unknown-keyword-alone-test
     (testing "a lone unrecognized option returns {} (not nil) so dependent code doesn't NPE"
       (let [[result err] (with-err-str* #(ut/parse-opts [:typo]))]
         (is (= {} result)
             "the cond fall-through used to return nil — :else recurs to terminate the loop with acc")
         (is (re-find #":typo" err))))))

#?(:clj
   (deftest parse-opts-unknown-trailing-keyword-test
     (testing "unknown option AFTER a recognized one still leaves the prior opts intact"
       (let [[result _] (with-err-str* #(ut/parse-opts [:once :verbose :onec]))]
         (is (= {:once true :verbose true} result)
             ":onec is the typo; :once and :verbose accumulate before it, the :else branch consumes :onec and the empty? branch returns acc")))))

(defn- once-state-entries []
  (:entries (deref @#'ut/once-state)))

(deftest once-emit-stores-fingerprints-not-results
  (testing ":once dedupes a large result without retaining the live value"
    (ut/-reset-once-state!)
    (let [large-result (vec (repeat 10000 {:payload :x}))]
      (is (true? (ut/-once-emit? "large-trace" 0 large-result)))
      (is (false? (ut/-once-emit? "large-trace" 0 (vec (repeat 10000 {:payload :x}))))
          "same value still dedupes when compared by fingerprint")
      (let [entry (get (once-state-entries) ["large-trace" 0])]
        (is (= (hash large-result) (:fingerprint entry)))
        (is (integer? (:seen-order entry)))
        (is (not (contains? entry :result))
            "state stores the fingerprint metadata, not the user result")
        (is (not= large-result entry)
            "the large value is not retained as the state value")))))

(deftest once-emit-prunes-old-entries
  (testing ":once state stays bounded as hot-reload-like trace ids accumulate"
    (ut/-reset-once-state!)
    (let [limit @#'ut/once-state-limit]
      (dotimes [i (inc limit)]
        (ut/-once-emit? (str "trace-" i) 0 i))
      (let [entries (once-state-entries)]
        (is (<= (count entries) limit))
        (is (contains? entries [(str "trace-" limit) 0])
            "the newest entry survives pruning")
        (is (not (contains? entries ["trace-0" 0]))
            "old orphaned entries are pruned")))))
