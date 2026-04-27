(ns day8.re-frame.debux.common.util
  "Utilities common for clojure and clojurescript"
  (:refer-clojure :exclude [coll?])
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.zip :as z]
            [clojure.walk :as walk]
            [cljs.analyzer.api :as ana]
            [clojure.repl :as repl]
            [re-frame.trace :as trace]))

(defn map->seq[m]
  (reduce
    (fn [r [k v]]
      (concat r [k v]))
    []
    m))
  
;;; zipper
(defn sequential-zip [root]
  (z/zipper #(or (sequential? %) (map? %)) 
            (fn [x]
              (cond 
                (map? x)    (with-meta (map->seq x) (meta x))
                :else       x))
            (fn [x children]
              (cond 
                (vector? x) (with-meta (vec children) (meta x))
                (map? x)    (with-meta
                              (reduce
                                (fn [r [k v]]
                                    (assoc r k v))
                                  {}
                                  (partition 2 children))
                              (meta x))
                :else children))
            root))

(defn right-or-next [loc]
  (if-let [right (z/right loc)]
    ;; in case of (... (+ a b) c) or (... a b)
    right
    (if (sequential? (z/node loc))
      (let [rightmost (-> loc z/down z/rightmost)]
        (if (sequential? (z/node rightmost))
          ;; in case of (... (+ a (* b c)))
          (recur rightmost)

          ;; in case of (... (+ a b))
          (-> rightmost z/next)))

      ;; in case of (... a)
      (-> loc z/next))))


;; Tidy up macroexpansions

(def auto-gensym-pattern #"(.*)__\d+__auto__")              ;; form#
(def anon-gensym-pattern #"G__\d+")                         ;; (gensym)
(def named-gensym-pattern #?(:clj #"(.*?)\d{4,}"            ;; (gensym 'form), must match at least 4 numbers so we don't catch symbols with trailing numbers
                             :cljs #"(.*?)\d{2,}"))         ;; (gensym 'form), must match at least 2 numbers for cljs so we don't catch symbols with trailing numbers
(def anon-param-pattern #"p(\d+)__\d+#")                    ;; #(%1 %2 %3)

(defn form-tree-seq [form]
  (tree-seq
    sequential?
    seq
    form))

(defn with-gensyms-names
  "Reverse gensym'd names to their original source form to make them easier to read."
  [form mapping]
  (let [gen-name (fn [result name]
                   (if-not (contains? result (str name "#"))
                     (str name "#")
                     (->> (iterate inc 2)
                          (map #(str name % "#"))
                          (filter #(not (contains? result %)))
                          (first))))
        name-for (fn [result sym-name]
                   (if-let [groups (re-matches auto-gensym-pattern sym-name)]
                     (gen-name result (second groups))
                     (if (re-matches anon-gensym-pattern sym-name)
                       (gen-name result "gensym")
                       (if-let [groups (re-matches named-gensym-pattern sym-name)]
                         (gen-name result (second groups))
                         (if-let [groups (re-matches anon-param-pattern sym-name)]
                           (str "%" (second groups)))))))]
    (reduce (fn [result sym-name]
              (if (contains? result sym-name)
                result
                (if-let [new-name (name-for result sym-name)]
                  (assoc result sym-name new-name)
                  result)))
            mapping
            (->> (form-tree-seq form)
                 (filter #(and (symbol? %) (nil? (namespace %))))
                 (map name)))))

(defn with-symbols-names
  "Tidy up fully qualified names that have aliases in the existing namespace."
  ;; TODO: handle FQN's other than clojure.core
  [form {:keys [context refers aliases] :as state} mapping]
  (reduce (fn [result sym]
            (if (= "clojure.core" (namespace sym))
              (assoc result (pr-str sym) (name sym))
              result))
          mapping
          (->> (form-tree-seq form)
               ;; TODO: use qualified-symbol? once we are on Clojure 1.9
               (filter #(and (symbol? %) (namespace %))))))

(defn tidy-macroexpanded-form
  "Takes a macroexpanded form and tidies it up to be more readable by
  unmapping gensyms and replacing fully qualified namespaces with aliases
  or nothing if the function is referred."
  [form state]
  ;; Mapping is a mapping of String:String which represent symbols
  (let [mapping (->> {}
                     (with-gensyms-names form)
                     (with-symbols-names form state))]
    (loop [loc (sequential-zip form)]
      (if (z/end? loc)
        (z/root loc)
        (if (symbol? (z/node loc))
          (recur (z/next (z/edit loc (fn [sym] (symbol (get mapping (pr-str sym) sym))))))
          (recur (z/next loc)))))))

;;; ----------------------------------------------------------------------
;;; Trace sink — re-frame-trace integration
;;; ----------------------------------------------------------------------
;;;
;;; The two functions below are the only writers to re-frame's trace
;;; stream from re-frame-debux. Everything in `dbgn`'s zipper walk
;;; eventually funnels through one of them.
;;;
;;; `send-form!` writes a single `:form` tag once per traced function
;;; invocation, carrying the outermost macroexpanded form.
;;;
;;; `send-trace!` is called once per instrumented sub-form during the
;;; function's body. It accumulates each call into a vector under the
;;; `:code` tag of the current trace event (`re-frame.trace/*current-trace*`).
;;;
;;; Each entry in `:code` has this shape — this is the contract that
;;; re-frame-10x's "Code" panel and any other downstream consumer
;;; (e.g. re-frame-pair surfacing :code as :debux/code in its epoch
;;; coercion) reads:
;;;
;;;   {:form          <hiccup-printable form, post tidy-macroexpanded-form>
;;;    :result        <evaluated value of that form>
;;;    :indent-level  <int, nesting depth in the original source>
;;;    :syntax-order  <int, position in evaluation order>
;;;    :num-seen      <int, count of duplicate emissions for :once dedup>}
;;;
;;; Field semantics:
;;;
;;; - **`:form`** is the user-readable source form that was traced.
;;;   `tidy-macroexpanded-form` (above, line 100ish) replaces fully-
;;;   qualified names from `clojure.core` with their short forms and
;;;   strips gensym-suffixed names from `let`/`loop`/`for`-introduced
;;;   bindings. The result is human-readable, but NOT necessarily
;;;   round-trippable through the reader if the source contained
;;;   reader-conditional or shadow-cljs-specific forms.
;;;
;;; - **`:result`** is the value the form evaluated to. Stored as the
;;;   live value (no pr-str coercion here — that's the consumer's call,
;;;   typically with `set-print-seq-length!` to bound large collections).
;;;
;;; - **`:indent-level`** mirrors the form's nesting depth in the source
;;;   (0 = top-level call within the fn body; 1 = one form deep; etc.).
;;;   re-frame-10x uses this for tree-view indentation.
;;;
;;; - **`:syntax-order`** is the form's position in evaluation order
;;;   (zipper-walk order, depth-first L→R). Stable across runs of the
;;;   same handler. Used for tie-breaking when `:indent-level` matches.
;;;
;;; - **`:num-seen`** counts how many times this exact form has been
;;;   emitted previously in the SAME trace event. Always 0 today; the
;;;   field is reserved for a future `:once` / dedup option (debux has
;;;   it; we don't yet — see docs/improvement-plan.md §4).
;;;
;;; Example payload shape after one traced dispatch through a handler
;;; defined as `(fn-traced [db [_ x]] (let [n (* 2 x)] (assoc db :n n)))`:
;;;
;;;   {:form (let [n (* 2 x)] (assoc db :n n))
;;;    :result {:n 10 ...}
;;;    :indent-level 0 :syntax-order 0 :num-seen 0}
;;;   {:form (* 2 x)         :result 10 :indent-level 1 :syntax-order 1 :num-seen 0}
;;;   {:form (assoc db :n n) :result {:n 10 ...} :indent-level 1 :syntax-order 2 :num-seen 0}
;;;
;;; Stability: this shape has been stable since v0.5.x. If a future
;;; release adds new fields, append-only is the contract — existing
;;; consumers keep working.

;;; Production-mode loud-fail check (improvement-plan §5(b)).
;;; If a release build accidentally bundles the live
;;; day8.re-frame.tracing namespace instead of swapping to the stubs
;;; (via :ns-aliases or the production profile), tracing runs in
;;; production with no signal — bloating the bundle and emitting
;;; trace noise into 10x. The first send-trace! call in such a build
;;; fires a one-shot `console.warn` so the operator notices.
;;;
;;; goog.DEBUG is the closure-define that's true under :optimizations
;;; :none / dev and false under :optimizations :advanced / release.
;;; A live trace path running with goog.DEBUG=false is the smoking
;;; gun for "release build, tracing not stubbed".

(defonce ^:private prod-mode-warned? (atom false))

(defn ^:private maybe-warn-production-mode! []
  #?(:cljs
     (when-not @prod-mode-warned?
       (try
         (when (false? js/goog.DEBUG)
           (reset! prod-mode-warned? true)
           (js/console.warn
            (str "re-frame-debux: send-trace! is firing in a build with "
                 "goog.DEBUG=false. The day8.re-frame.tracing namespace "
                 "is loaded and active in what looks like a production "
                 "build (advanced compilation usually sets goog.DEBUG to "
                 "false). This bloats your bundle and emits trace noise "
                 "into 10x. Check your build config: shadow-cljs users "
                 "should set :ns-aliases to redirect day8.re-frame.tracing "
                 "→ day8.re-frame.tracing-stubs in release builds; "
                 "lein/cljsbuild users should put day8.re-frame/tracing-stubs "
                 "in the production profile instead of day8.re-frame/tracing. "
                 "See https://github.com/day8/re-frame-debux#two-libraries "
                 "for details. (This warning fires once per session.)")))
         (catch :default _
           ;; If goog.DEBUG isn't accessible (bare CLJS without Closure?),
           ;; mark as warned so we don't keep retrying.
           (reset! prod-mode-warned? true))))
     :clj nil))

(defn send-form! [form]
  (maybe-warn-production-mode!)
  (trace/merge-trace! {:tags {:form form}}))

(defn send-trace! [code-trace]
  (maybe-warn-production-mode!)
  (let [code  (get-in trace/*current-trace* [:tags :code] [])
        ;; :locals is an optional extension key
        ;; emitted by trace* when fn-traced was called with
        ;; {:locals true}. It carries [[sym val] ...] pairs captured
        ;; from the function args. Whitelisted explicitly so the
        ;; payload contract stays small (10x's Code panel reads
        ;; specific keys; merging arbitrary extras would be brittle).
        ;; :name (label from a `dbg` call) follows the same
        ;; whitelist convention; consumers can branch on its presence.
        entry (cond-> {:form         (tidy-macroexpanded-form (:form code-trace) {})
                       :result       (:result code-trace)
                       :indent-level (:indent-level code-trace)
                       :syntax-order (:syntax-order code-trace)
                       :num-seen     (:num-seen code-trace)}
                (contains? code-trace :locals)
                (assoc :locals (:locals code-trace))
                (contains? code-trace :name)
                (assoc :name (:name code-trace)))]
    ;; TODO: also capture macroexpanded form? Might be useful in some cases?
    (trace/merge-trace! {:tags {:code (conj code entry)}})))

;; Sink dispatch for the dbg macro. Inside a re-frame trace
;; event (`*current-trace*` bound non-nil during with-trace) accumulate
;; onto the active event's :tags :code via send-trace!. Outside, fall
;; back to tap> so REPL callers still see output. Out-of-trace tap>
;; payloads carry `:debux/dbg true` so `add-tap` consumers can branch.
;;
;; Why a separate helper rather than inlining in the macro: keeps the
;; `*current-trace*` reference + the tap-fallback logic in CLJ source
;; (testable via plain deftest) instead of macro-expanded boilerplate
;; at every dbg call-site.

(defn send-trace-or-tap!
  "If a re-frame trace event is in flight, accumulate `payload` onto
   the active event's :tags :code via `send-trace!`. Otherwise tap>
   so REPL callers still see output.

   `tap-also?` — when true, ALSO tap> alongside the in-trace emit
   (for callers that want both signals). Out-of-trace, tap> always
   fires regardless. Returns nil."
  [payload tap-also?]
  (if (some? trace/*current-trace*)
    (do (send-trace! payload)
        (when tap-also? (tap> (assoc payload :debux/dbg true))))
    (tap> (assoc payload :debux/dbg true)))
  nil)

;;; ----------------------------------------------------------------------
;;; Frame markers — entry/exit bracketing for fn-traced / defn-traced
;;; ----------------------------------------------------------------------
;;;
;;; Each fn-traced'd / defn-traced'd handler invocation emits a pair of
;;; markers onto the active trace's :tags :trace-frames vector:
;;;
;;;   {:phase :enter :frame-id "frame_42" :t <ms>}
;;;   {:phase :exit  :frame-id "frame_42" :t <ms> :result <value>}
;;;
;;; The frame-id is a gensym'd string baked into the macroexpansion at
;;; the call site; both markers carry the same id so a consumer (10x
;;; Code panel, custom inspector) can pair them and bracket the
;;; intermediate :code entries that landed between them.
;;;
;;; Off-trace (no `*current-trace*`) — markers are silently dropped.
;;; Frame markers are framework-level boundary info, not user-visible
;;; data, so unlike `send-trace-or-tap!` there's no tap> fallback.
;;;
;;; Exception path: only :enter is guaranteed. If the wrapped body
;;; throws, no :exit marker fires — consumers can detect a missing
;;; :exit (a :enter with no matching :exit by frame-id) as a thrown
;;; invocation. Wrapping in try/finally to always emit :exit was
;;; considered and rejected: an exception with no result is a
;;; meaningfully different signal than a successful return, and the
;;; missing-exit pattern preserves that signal.

(defn- now-ms []
  #?(:cljs (.now js/Date)
     :clj  (System/currentTimeMillis)))

(defn -send-frame-enter!
  "Emit a `:enter` marker on the active trace's :trace-frames vector.
   No-op when no trace is in flight. Internal — called by the
   fn-traced / defn-traced expansion at body-entry."
  [frame-id]
  (when (some? trace/*current-trace*)
    (let [frames (get-in trace/*current-trace* [:tags :trace-frames] [])]
      (trace/merge-trace!
        {:tags {:trace-frames (conj frames
                                    {:phase    :enter
                                     :frame-id frame-id
                                     :t        (now-ms)})}})))
  nil)

(defn -send-frame-exit!
  "Emit an `:exit` marker carrying the body's return value. Same
   no-op-off-trace policy as -send-frame-enter!. Internal — called by
   the fn-traced / defn-traced expansion right before returning."
  [frame-id result]
  (when (some? trace/*current-trace*)
    (let [frames (get-in trace/*current-trace* [:tags :trace-frames] [])]
      (trace/merge-trace!
        {:tags {:trace-frames (conj frames
                                    {:phase    :exit
                                     :frame-id frame-id
                                     :t        (now-ms)
                                     :result   result})}})))
  nil)

;;; ----------------------------------------------------------------------
;;; :once / duplicate-suppression state
;;; ----------------------------------------------------------------------
;;;
;;; When `:once` is set on `fn-traced` / `defn-traced` / `dbg` / `dbgn`,
;;; emission is gated on whether the (form, result) pair has been seen
;;; before. The atom below holds the per-form last-result so that
;;; consecutive identical emissions get suppressed across handler
;;; invocations — i.e. dispatching the same event twice with unchanged
;;; inputs produces a `:code` payload on the FIRST dispatch and an
;;; empty (or smaller) one on the SECOND.
;;;
;;; Form identity is `[trace-id syntax-order]`:
;;;
;;; - **`trace-id`** is a gensym'd string baked into the macroexpansion
;;;   of `dbgn-forms` / `dbgn` / `dbg`. One trace-id per macro
;;;   expansion site — stable across runtime invocations of the same
;;;   compiled handler, distinct between separate fn-traced'd handlers
;;;   in the same file.
;;; - **`syntax-order`** is the per-form index assigned by `insert-trace`
;;;   during the zipper walk (depth-first, left-to-right). Stable
;;;   across runs of the same body.
;;;
;;; Lifecycle: process-local atom; resets only on explicit
;;; `-reset-once-state!`. Hot-reload of a macro-call site produces a
;;; fresh `trace-id` (the gensym is re-generated), so old keys for
;;; that site become orphaned but harmless. Tests reset the atom in
;;; the trace-capture fixture so dedup state doesn't leak across
;;; deftests.
(defonce ^:private once-state (atom {}))

(defn -reset-once-state!
  "Drop all `:once` dedup state. Used by the integration-test fixture
   (so cross-test contamination doesn't make a previous test's last
   emission silence the next one) and exposed publicly so REPL callers
   running a long live-debug session can clear the slate without
   waiting for a hot-reload to invalidate keys."
  []
  (reset! once-state {}))

(defn -once-emit?
  "Returns true if a `:once`-gated form should emit its trace right
   now, false if the same (form, result) pair was the most recent
   emission and should be suppressed.

   Side effect: when emit-allowed (returns true), the new result is
   recorded as the latest for `[trace-id syntax-order]`, so the next
   call with the same result returns false.

   `nil` and `false` are distinguishable from `::unseen` (the sentinel
   for 'never emitted'), so a form that legitimately produces a stable
   `nil` result emits ONCE (on first sighting) and then dedupes."
  [trace-id syntax-order new-result]
  (let [k    [trace-id syntax-order]
        prev (get @once-state k ::unseen)]
    (if (and (not= prev ::unseen) (= prev new-result))
      false
      (do (swap! once-state assoc k new-result)
          true))))

;;; For internal debugging
(defmacro d
  "The internal macro to debug dbg macro.
   <form any> a form to be evaluated"
  [form]
  `(let [return# ~form]
     (println ">> dbg_:" (pr-str '~form) "=>\n" (pr-str return#) "<<")
     return#))


;;; indent-level control
(def indent-level* (atom 1))

(defn reset-indent-level! []
  (reset! indent-level* 1))


;;; print-seq-length
(def print-seq-length* (atom 100))

(defn set-print-seq-length! [num]
  (reset! print-seq-length* num))


;;; general
(defmacro read-source [sym]
  `(-> (repl/source ~sym)
       with-out-str
       read-string))

(defn cljs-env? [env]
  (boolean (:ns env)))

(defn vec->map
  "Transsub-forms a vector into an array-map with key/value pairs.
  (def a 10)
  (def b 20)
  (vec-map [a b :c [30 40]])
  => {:a 10 :b 20 ::c :c :[30 40] [30 40]}"
  [v]
  (apply array-map
         (mapcat (fn [elm]
                   `[~(keyword (str elm)) ~elm])
                 v)))

(defn replace-& [v]
  (walk/postwalk-replace {'& ''&} v))



;;; symbol with namespace
#?(:clj
   (defn- var->symbol [v]
     (let [m    (meta v)
           ns   (str (ns-name (:ns m)))
           name (str (:name m))]
       (symbol ns name))))

#?(:clj
   (defn- ns-symbol-for-clj [sym]
     (if-let [v (resolve sym)]
       (var->symbol v)
       sym)))

#?(:clj
   (defn- ns-symbol-for-cljs [sym env]
     (if-let [meta (ana/resolve env sym)]
       ;; normal symbol
       (let [[ns name] (str/split (str (:name meta)) #"/")]
         ;; The special symbol `.` must be handled in the following special symbol part.
         ;; However, the special symbol `.` returns meta {:name / :ns nil}, which may be a bug.
         (if (nil? ns)
           sym
           (symbol ns name)))
       ;; special symbols except for `.`
       sym)))

#?(:clj
   (defn ns-symbol [sym & [env]]
     (if (symbol? sym)
       (if (cljs-env? env)
         (ns-symbol-for-cljs sym env)
         (ns-symbol-for-clj sym))
       sym)))


;;; print
(defn take-n-if-seq [n result]
  (if (seq? result)
    (take (or n @print-seq-length*) result)
    result))

(defn truncate [s]
  (if (> (count s) 70)
    (str (.substring s 0 70) " ...")
    s))

(defn- make-bars-
  [times]
  (apply str (repeat times "|")))

(def make-bars (memoize make-bars-))

(defn prepend-bars
  [line indent-level]
  (str (make-bars indent-level) " " line))

(defn print-form-with-indent
  [form indent-level]
  ;; TODO: trace this information somehow
  (println (prepend-bars form indent-level))
  (flush))

(defn form-header [form & [msg]]
  (str (truncate (pr-str form))
       (and msg (str "   <" msg ">"))
       " =>"))


(defn prepend-blanks
  [lines]
  (mapv #(str "  " %) lines))

(defn pprint-result-with-indent
  [result indent-level]
  ;; TODO: trace this information somehow
  (let [res    result
        result (with-out-str (pp/pprint res))
        pprint (str/trim result)]
    (println (->> (str/split pprint #"\n")
                  prepend-blanks
                  (mapv #(prepend-bars % indent-level))
                  (str/join "\n")))
    (flush)))

(defn insert-blank-line []
  (println " ")
  (flush))


;;; parse options
(defn parse-opts
  [opts]
  (loop [opts opts
         acc  {}]
    (let [f (first opts)
          s (second opts)]
      (cond
        (empty? opts)
        acc

        (number? f)
        (recur (next opts) (assoc acc :n f))

        (string? f)
        (recur (next opts) (assoc acc :msg f))

        (= f :if)
        (recur (nnext opts) (assoc acc :condition s))

        ;;; for clojurescript
        (= f :js)
        (recur (next opts) (assoc acc :js true))

        (#{:once :o} f)
        (recur (next opts) (assoc acc :once true))

        (#{:verbose :show-all} f)
        (recur (next opts) (assoc acc :verbose true))

        (#{:style :s} f)
        (recur (nnext opts) (assoc acc :style s))

        (= f :clog)
        (recur (next opts) (assoc acc :clog true))))))


;;; quote the value parts of a map
(defn quote-val [[k v]]
  `[~k '~v])

(defn quote-vals [m]
  (->> (map quote-val m)
       (into {})))


;;; for recur processing
(defn include-recur? [form]
  (((comp set flatten) form) 'recur))

#?(:clj
   (defn final-target? [sym targets env]
     (let [ns-sym (ns-symbol sym env)]
       (or (get targets ns-sym)
           (some #(= % ns-sym)
                 '[clojure.core/defn clojure.core/defn- clojure.core/fn
                   cljs.core/defn cljs.core/defn- cljs.core/fn])))))

(defn o-skip?
  "True iff `sym` is the fully-qualified `o-skip` macro name. Used by
   `insert-o-skip-for-recur` (skip.cljc) to detect a node that's
   already been wrapped on a prior pass and avoid re-wrapping.

   The fqn was `'debux.common.macro-specs/o-skip` — left over from
   the upstream philoskim/debux library before this fork renamed the
   namespace to `day8.re-frame.debux.common.macro-specs`. With the
   stale fqn the predicate ALWAYS returned false, so the recur-walker
   re-wrapped already-wrapped nodes and `dbgn` macroexpansion
   diverged on `loop`+`recur` (issue #40).

   Other callers (skip-place? at line 478ish) already used the right
   fqn — only this defn was stale."
  [sym]
  (= 'day8.re-frame.debux.common.macro-specs/o-skip sym))

(declare remove-d)

;;; spy functions
(def spy-first
  (fn [result quoted-form indent]
    (assert (integer? indent) (str "indent was not correctly replaced for form " (prn-str quoted-form) "\nThis is a bug, please report it to https://github.com/Day8/re-frame-debux"))
    (send-trace! {:form (remove-d quoted-form 'dummy) :result result :indent-level indent})
    ;(print-form-with-indent (form-header quoted-form) indent)
    ;(pprint-result-with-indent (take-n-if-seq 100 result) indent)
    result))

(def spy-last
  (fn [quoted-form indent result]
    (assert (integer? indent) (str "indent was not correctly replaced for form " (prn-str quoted-form) "\nThis is a bug, please report it to https://github.com/Day8/re-frame-debux"))
    (send-trace! {:form (remove-d quoted-form 'dummy) :result result :indent-level indent})
    ;(print-form-with-indent (form-header quoted-form) indent)
    ;(pprint-result-with-indent (take-n-if-seq 100 result) indent)
    result))

(defn spy-comp [quoted-form indent form]
  (fn [& arg]
    (let [result (apply form arg)]
      (assert (integer? indent) (str "indent was not correctly replaced for form " (prn-str quoted-form) "\nThis is a bug, please report it to https://github.com/Day8/re-frame-debux"))
      (send-trace! {:form (remove-d quoted-form 'dummy) :result result :indent-level indent})
      ;(print-form-with-indent (form-header quoted-form) indent)
      ;(pprint-result-with-indent (take-n-if-seq 100 result) indent)
      result)))

;; Remove trace info

(defn debux-skip-symbol? [sym]
  (contains? #{'day8.re-frame.debux.common.macro-specs/skip-outer
               'day8.re-frame.debux.common.macro-specs/skip
               'day8.re-frame.debux.common.macro-specs/o-skip
               :day8.re-frame.debux.common.macro-specs/skip-place}
             sym))

(defn spy-first? [sym]
  (= 'day8.re-frame.debux.common.util/spy-first sym))

(defn remove-d [form d-sym]
  ;; TODO: should we instead look to rewrite the quoted/spied forms
  ;; at macro compile time, rather than filtering them out
  ;; when the trace is being emitted?
  (loop [loc (sequential-zip form)]
    (let [node (z/node loc)]
      ;(ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;; in case of (d ...)
        (and (seq? node)
             (or (= d-sym (first node))
                 (debux-skip-symbol? (first node))
                 (spy-first? (first node))))
        ;; We take the third node, because the first two are
        ;; (d <indent-level> ...)
        (recur (z/replace loc (last node)))

        ;; in case of spy-last
        (and (seq? node)
             (= `spy-last (first node)))
        (recur (z/replace loc (last node)))

        :else
        (recur (z/next loc))))))
