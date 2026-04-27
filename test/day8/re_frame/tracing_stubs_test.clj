(ns day8.re-frame.tracing-stubs-test
  "Tests for the production-mode stub macros at
   `day8.re-frame.tracing-stubs` (CLJ file: src/day8/re_frame/
   tracing_stubs.cljc) and the parallel jar at tracing-stubs/src/
   day8/re_frame/tracing.cljc.

   The stubs replace the live tracing macros in release builds via
   `:ns-aliases` (see README \"Two libraries\"). They MUST accept the
   same call shapes as the live macros — including the leading opts
   map introduced for fn-traced / defn-traced — and compile out to
   plain `fn` / `defn` / form passthrough so production builds incur
   zero runtime cost.

   The trap this regression suite plugs: a user who writes
   `(fn-traced {:locals true} [db ev] body)` works in dev but the
   release build fails to compile, because clojure.core/fn rejects a
   map literal in the args slot. Surfaces only at release time —
   exactly the kind of error the production-mode hot-swap is
   supposed to be invisible against. We pin macroexpansion shape
   here.

   Both stub files (the in-src `day8.re-frame.tracing-stubs` ns and
   the tracing-stubs/ subproject's `day8.re-frame.tracing` ns) carry
   identical macro bodies and must stay in sync. We exercise the
   in-src one via macroexpand-1; the subproject ns is unreachable
   from the parent test classpath because it collides with the live
   `day8.re-frame.tracing` ns at src/. The textual sync between the
   two files is the safety net there."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame.tracing-stubs]))

(deftest fn-traced-stub-strips-opts-map
  (testing "fn-traced stub with leading opts map drops it and emits bare fn"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/fn-traced
                              {:locals true :if odd? :once true}
                              [x] x))]
      (is (= '(clojure.core/fn [x] x) r)
          "opts map is stripped — fn does not see it in its args slot")))

  (testing "fn-traced stub without opts still compiles to bare fn"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/fn-traced [x] x))]
      (is (= '(clojure.core/fn [x] x) r)
          "no-opts call shape unchanged")))

  (testing "fn-traced stub with name and opts strips opts but keeps name"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/fn-traced
                              {:verbose true}
                              my-fn [x] x))]
      (is (= '(clojure.core/fn my-fn [x] x) r)
          "named fn-traced — opts dropped, name preserved")))

  (testing "fn-traced stub with multi-arity body and opts"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/fn-traced
                              {:locals true}
                              ([x] x)
                              ([x y] (+ x y))))]
      (is (= '(clojure.core/fn ([x] x) ([x y] (+ x y))) r)
          "multi-arity bodies preserved — opts dropped"))))

(deftest defn-traced-stub-strips-opts-map
  (testing "defn-traced stub with leading opts map drops it and emits bare defn"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/defn-traced
                              {:if some?}
                              my-fn [x] x))]
      (is (= '(clojure.core/defn my-fn [x] x) r)
          "opts map is stripped — defn does not see it in its args slot")))

  (testing "defn-traced stub without opts still compiles to bare defn"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/defn-traced
                              my-fn [x] x))]
      (is (= '(clojure.core/defn my-fn [x] x) r)
          "no-opts call shape unchanged")))

  (testing "defn-traced stub with opts + docstring + attr-map preserves the trailing pieces"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/defn-traced
                              {:locals true}
                              my-fn
                              "the docstring"
                              {:private true}
                              [x] x))]
      (is (= '(clojure.core/defn my-fn "the docstring" {:private true} [x] x) r)
          "docstring + attr-map ride along; opts dropped from the front")))

  (testing "defn-traced stub with multi-arity body and opts"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/defn-traced
                              {:once true}
                              my-fn
                              ([x] x)
                              ([x y] (+ x y))))]
      (is (= '(clojure.core/defn my-fn ([x] x) ([x y] (+ x y))) r)
          "multi-arity bodies preserved — opts dropped"))))

(deftest fx-traced-stub-strips-opts-map
  (testing "fx-traced stub still strips opts (regression — pinned by sibling fix)"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/fx-traced
                              {:locals true}
                              [_ [_ amount]] {:db {:total amount}}))]
      (is (= '(clojure.core/fn [_ [_ amount]] {:db {:total amount}}) r)
          "fx-traced stub already strips — verify it stays that way"))))

(deftest defn-fx-traced-stub-strips-opts-map
  (testing "defn-fx-traced stub still strips opts (regression — pinned by sibling fix)"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/defn-fx-traced
                              {:locals true}
                              checkout [_ [_ amount]] {:db {:total amount}}))]
      (is (= '(clojure.core/defn checkout [_ [_ amount]] {:db {:total amount}}) r)
          "defn-fx-traced stub already strips — verify it stays that way"))))

(deftest stub-files-have-identical-fn-defn-traced-bodies
  (testing "the in-src and subproject stub files keep fn-traced/defn-traced in sync"
    (let [in-src    (slurp "src/day8/re_frame/tracing_stubs.cljc")
          subproj   (slurp "tracing-stubs/src/day8/re_frame/tracing.cljc")
          ;; Pull just the fn-traced and defn-traced defmacro forms
          ;; out of each file and compare. The two files have
          ;; different namespace declarations and the in-src one
          ;; carries extra dbg / dbgn / dbg-last stubs, so we can't
          ;; do a whole-file diff — but the four shared macros must
          ;; match byte-for-byte.
          extract   (fn [src macro-name]
                      (let [start (.indexOf ^String src (str "(defmacro " macro-name))]
                        (when (neg? start)
                          (throw (ex-info (str "macro not found: " macro-name) {:src-len (count src)})))
                        ;; Walk forward, balancing parens, to find the
                        ;; closing `)` of the defmacro form.
                        (loop [i     (inc start)
                               depth 1]
                          (cond
                            (zero? depth) (subs src start i)
                            (= \( (.charAt ^String src i)) (recur (inc i) (inc depth))
                            (= \) (.charAt ^String src i)) (recur (inc i) (dec depth))
                            :else (recur (inc i) depth)))))]
      (doseq [m ["defn-traced" "fn-traced" "fx-traced" "defn-fx-traced"]]
        (is (= (extract in-src m) (extract subproj m))
            (str m " stub diverges between the two stub files — they must be byte-identical"))))))
