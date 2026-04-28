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
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [day8.re-frame.tracing]
            [day8.re-frame.tracing.runtime]
            [day8.re-frame.tracing-stubs]
            [day8.re-frame.tracing-stubs.runtime]))

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

(deftest dbg-stub-returns-form-unchanged
  (testing "dbg stub with no opts returns the form"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/dbg (+ 1 2)))]
      (is (= '(+ 1 2) r)
          "single-form dbg — passthrough, no trace")))

  (testing "dbg stub with opts ignores them and returns the form"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/dbg (+ 1 2) {:msg "hi"}))]
      (is (= '(+ 1 2) r)
          "opts arg is discarded"))))

(deftest dbgn-stub-returns-form-unchanged
  (testing "dbgn stub returns the top-level form, opts ignored"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/dbgn (let [x 1] (inc x))))]
      (is (= '(let [x 1] (inc x)) r)
          "dbgn passthrough — nested form returned as-is")))

  (testing "dbgn stub with trailing opts ignores them"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/dbgn (+ 1 2) :msg "label"))]
      (is (= '(+ 1 2) r)
          "opts discarded"))))

(deftest dbg-last-stub-returns-value-unchanged
  (testing "dbg-last stub with value only returns it"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/dbg-last [1 2 3]))]
      (is (= '[1 2 3] r)
          "thread-last passthrough — value returned as-is")))

  (testing "dbg-last stub with leading opts returns the value and drops opts"
    (let [r (macroexpand-1 '(day8.re-frame.tracing-stubs/dbg-last {:msg "x"} [1 2 3]))]
      (is (= '[1 2 3] r)
          "opts arg discarded, value returned"))))

(deftest stub-files-have-identical-macro-bodies
  (testing "the in-src and subproject stub files keep all shared macros and
            no-op configuration defns in sync"
    (let [in-src    (slurp "src/day8/re_frame/tracing_stubs.cljc")
          subproj   (slurp "tracing-stubs/src/day8/re_frame/tracing.cljc")
          extract   (fn [src kind name]
                      (let [marker (str "(" kind " " name)
                            start  (.indexOf ^String src marker)]
                        (when (neg? start)
                          (throw (ex-info (str kind " not found: " name) {:src-len (count src)})))
                        (loop [i     (inc start)
                               depth 1]
                          (cond
                            (zero? depth) (subs src start i)
                            (= \( (.charAt ^String src i)) (recur (inc i) (inc depth))
                            (= \) (.charAt ^String src i)) (recur (inc i) (dec depth))
                            :else (recur (inc i) depth)))))]
      (doseq [m ["defn-traced" "fn-traced" "fx-traced" "defn-fx-traced"
                 "dbg" "dbgn" "dbg-last"]]
        (is (= (extract in-src "defmacro" m) (extract subproj "defmacro" m))
            (str m " stub diverges between the two stub files — they must be byte-identical")))
      (doseq [f ["set-tap-output!" "set-trace-frames-output!"
                 "set-print-seq-length!" "reset-indent-level!"]]
        (is (= (extract in-src "defn" f) (extract subproj "defn" f))
            (str f " stub diverges between the two stub files — they must be byte-identical"))))))

;; ---------------------------------------------------------------------------
;; Runtime API stubs — wrap-handler! / wrap-event-fx! / wrap-event-ctx! /
;; wrap-sub! / wrap-fx! macros must compile down to bare reg-event-db /
;; reg-sub / reg-fx / reg-event-fx / reg-event-ctx with NO fn-traced wrap.
;;
;; The dev-side runtime ns lives at src/day8/re_frame/tracing/runtime.cljc
;; and is unreachable from this test classpath under the same name as the
;; subproject stub (tracing-stubs/src/day8/re_frame/tracing/runtime.cljc)
;; — exactly the same shadow situation as the tracing.cljc stub. The
;; macroexpand checks below run against the in-src counterpart
;; (day8.re-frame.tracing-stubs.runtime), and the bodies-byte-identical
;; test pins it to the subproject ns by text comparison.
;; ---------------------------------------------------------------------------

(deftest runtime-stubs-expand-without-fn-traced
  (testing "every wrap-* stub expands to bare re-frame.core/reg-* — no fn-traced
            anywhere in the expansion. This is the zero-cost contract: a release
            build that picks up these stubs incurs no per-handler trace overhead
            and no zipper walking."
    (doseq [[label form expected-reg]
            [["wrap-handler! :event"
              '(day8.re-frame.tracing-stubs.runtime/wrap-handler! :event :foo (fn [db ev] db))
              "reg-event-db"]
             ["wrap-handler! :event opts"
              '(day8.re-frame.tracing-stubs.runtime/wrap-handler! :event :foo {:locals true} (fn [db ev] db))
              "reg-event-db"]
             ["wrap-event-fx!"
              '(day8.re-frame.tracing-stubs.runtime/wrap-event-fx! :foo (fn [_ _] {}))
              "reg-event-fx"]
             ["wrap-event-fx! opts"
              '(day8.re-frame.tracing-stubs.runtime/wrap-event-fx! :foo {:msg "stub"} (fn [_ _] {}))
              "reg-event-fx"]
             ["wrap-event-ctx!"
              '(day8.re-frame.tracing-stubs.runtime/wrap-event-ctx! :foo (fn [ctx] ctx))
              "reg-event-ctx"]
             ["wrap-event-ctx! opts"
              '(day8.re-frame.tracing-stubs.runtime/wrap-event-ctx! :foo {:once true} (fn [ctx] ctx))
              "reg-event-ctx"]
             ["wrap-sub!"
              '(day8.re-frame.tracing-stubs.runtime/wrap-sub! :foo (fn [_ q] q))
              "reg-sub"]
             ["wrap-sub! opts"
              '(day8.re-frame.tracing-stubs.runtime/wrap-sub! :foo {:verbose true} (fn [_ q] q))
              "reg-sub"]
             ["wrap-sub! :<-"
              '(day8.re-frame.tracing-stubs.runtime/wrap-sub! :foo :<- [:bar] (fn [bar _] bar))
              "reg-sub"]
             ["wrap-fx!"
              '(day8.re-frame.tracing-stubs.runtime/wrap-fx! :foo (fn [v] v))
              "reg-fx"]
             ["wrap-fx! opts"
              '(day8.re-frame.tracing-stubs.runtime/wrap-fx! :foo {:if some?} (fn [v] v))
              "reg-fx"]]]
      (testing label
        (let [r-str (pr-str (macroexpand form))]
          (is (re-find (re-pattern (str "re-frame\\.core/" expected-reg)) r-str)
              (str label " stub expansion includes " expected-reg))
          (is (nil? (re-find #"fn-traced" r-str))
              (str label " stub expansion has NO fn-traced — the zero-cost contract")))))))

(deftest runtime-stub-files-have-identical-bodies
  (testing "the in-src and subproject runtime stub files keep all shared
            forms (defonce + macros + defns) byte-identical. The two files
            diverge only in the ns form (docstring + ns name); everything
            from the (:require [re-frame.core]) onwards is the same source
            text so a future edit to one without the other gets caught here."
    (let [in-src         (slurp "src/day8/re_frame/tracing_stubs/runtime.cljc")
          subproj        (slurp "tracing-stubs/src/day8/re_frame/tracing/runtime.cljc")
          require-marker "(:require [re-frame.core]))"
          after-require  (fn [src]
                           (let [i (.indexOf ^String src require-marker)]
                             (when (neg? i)
                               (throw (ex-info "missing require marker"
                                               {:src-len (count src)})))
                             (subs src (+ i (count require-marker)))))]
      (is (= (after-require in-src) (after-require subproj))
          "runtime stub bodies must be byte-identical between the two files"))))

(deftest stub-runtime-ns-has-every-public-var
  (testing "the production stub at day8.re-frame.tracing-stubs.runtime
            advertises every public var that the dev-side
            day8.re-frame.tracing.runtime advertises. Under the production
            two-jar swap (or :ns-aliases swap) any caller that name-resolves
            against the dev ns must keep resolving against the stub. A
            missing stub var would surface as an unbound-var error at
            release-build compile / load time — exactly the silent
            dev/prod divergence this stub jar exists to prevent."
    (let [dev-vars  (set (keys (ns-publics 'day8.re-frame.tracing.runtime)))
          stub-vars (set (keys (ns-publics 'day8.re-frame.tracing-stubs.runtime)))
          missing   (set/difference dev-vars stub-vars)]
      (is (empty? missing)
          (str "stub ns is missing public vars present in the dev ns: "
               (sort missing))))))

(deftest stub-tracing-ns-has-runtime-config-knobs
  (testing "the production stub at day8.re-frame.tracing-stubs advertises the
            runtime configuration knobs that consumers wire at app boot —
            set-tap-output!, set-trace-frames-output!,
            set-print-seq-length!, reset-indent-level!. Without the stubs,
            a release build with these calls in the boot path would fail to
            load against an unbound-var error."
    (let [stub-vars (set (keys (ns-publics 'day8.re-frame.tracing-stubs)))]
      (doseq [v '[set-tap-output! set-trace-frames-output!
                  set-print-seq-length! reset-indent-level!]]
        (is (contains? stub-vars v)
            (str v " missing from day8.re-frame.tracing-stubs — release builds will 404"))))))
