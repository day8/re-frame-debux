(ns debux.dbgn
  (:require [clojure.zip :as z]
            [cljs.analyzer :as analyzer]
            [debux.common.macro-specs :as ms]
            [debux.common.skip :as sk]
            [debux.common.util :as ut :refer [remove-d]]
            [debux.macro-types :as mt]
            [debux.cs.macro-types :as cs.mt]
            [re-frame.trace :as trace]
            #_[zprint.core :as zp]))

;;; Basic strategy for dbgn

;; 1. original form
;;
;; (let [a 10
;;       b (+ a 20)]
;;   (+ a b))

;; 2. after insert-skip
;;
;; (let (o-skip [(skip a) 10
;;               (skip b) (+ a 20)])
;;   (+ a b))

;; 3. after insert-d
;;
;; (d (let (o-skip [(skip a) 10
;;                  (skip b) (d (+ (d a) 20))])
;;      (d (+ (d a) (d b)))))

;; 4. after remove-skip
;;
;; (d (let [a 10
;;          b (d (+ (d a) 20))]
;;      (d (+ (d a) (d b))))


(defn- macro-types [env]
  (if (ut/cljs-env? env)
    @cs.mt/macro-types*
    @mt/macro-types*))

;;; insert skip
(defn insert-skip
  "Marks the form to skip."
  [form env]
  ;(println "INSERT-SKIP" form env)
  ;(println "SEQ ZIP" (z/node (ut/sequential-zip form)))
  (loop [loc (ut/sequential-zip form)]
    (let [node (z/node loc)]
      ;(ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;; in case of (skip ...)
        (and (seq? node) (= `ms/skip (first node)))
        (recur (ut/right-or-next loc))

        (and (seq? node) (symbol? (first node)))
        (let [sym (ut/ns-symbol (first node) env)]
          ;(ut/d sym)
          (cond
            ((:def-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-def node))
                z/next
                recur)

            ((:defn-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-defn node))
                z/next
                recur)

            ((:fn-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-fn node))
                z/next
                recur)


            (or ((:let-type (macro-types env)) sym)
                ((:loop-type (macro-types env)) sym))
            (-> (z/replace loc (sk/insert-skip-in-let node))
                z/next
                recur)

            ((:letfn-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-letfn node))
                z/next
                recur)


            ((:for-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-for node))
                z/next
                recur)

            ((:case-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-case node))
                z/next
                recur)


            ((:skip-arg-1-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-arg-1 node))
                z/next
                recur)

            ((:skip-arg-2-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-arg-2 node))
                z/next
                recur)

            ((:skip-arg-1-2-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-arg-1-2 node))
                z/next
                recur)

            ((:skip-arg-2-3-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-arg-2-3 node))
                z/next
                recur)

            ((:skip-arg-1-3-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-arg-1-3 node))
                z/next
                recur)

            ((:skip-form-itself-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-form-itself node))
                ut/right-or-next
                recur)

            ((:thread-first-type (macro-types env)) sym)
            (do #_(println "SYMLOCNODE" loc node)
              (let [new-node (sk/insert-spy-first node)]
                ;(zp/czprint new-node)
                ;(println "NEW" (macroexpand-1 new-node))
                (recur (z/replace loc (if (ut/cljs-env? env)
                                        (analyzer/macroexpand-1 {} new-node)
                                        (macroexpand-1 new-node))))))

            ((:thread-last-type (macro-types env)) sym)
            (let [new-node (sk/insert-spy-last node)]
              (recur (z/replace loc (if (ut/cljs-env? env)
                                      (analyzer/macroexpand-1 {} new-node)
                                      (macroexpand-1 new-node)))))

            ((:some-first-type (macro-types env)) sym)
            (let [new-node (sk/skip-some-> node)]
              (recur (z/replace loc (if (ut/cljs-env? env)
                                      (analyzer/macroexpand-1 {} new-node)
                                      (macroexpand-1 new-node)))))

            ((:some-last-type (macro-types env)) sym)
            (let [new-node (sk/skip-some->> node)]
              (recur (z/replace loc (if (ut/cljs-env? env)
                                      (analyzer/macroexpand-1 {} new-node)
                                      (macroexpand-1 new-node)))))

            ((:cond-first-type (macro-types env)) sym)
            (let [new-node (sk/skip-cond-> node)]
              (recur (z/replace loc (if (ut/cljs-env? env)
                                      (analyzer/macroexpand-1 {} new-node)
                                      (macroexpand-1 new-node)))))


            ((:cond-last-type (macro-types env)) sym)
            (let [new-node (sk/skip-cond->> node)]
              (recur (z/replace loc (if (ut/cljs-env? env)
                                      (analyzer/macroexpand-1 {} new-node)
                                      (macroexpand-1 new-node)))))

            ((:condp-type (macro-types env)) sym)
            (let [new-node (sk/skip-condp node)]
              (recur (z/replace loc (if (ut/cljs-env? env)
                                      (analyzer/macroexpand-1 {} new-node)
                                      (macroexpand-1 new-node)))))

            ; TODO: add comment about this one being different
            ((:expand-type (macro-types env)) sym)
            ;; Why do we add a seq call here?
            (-> (z/replace loc (seq (if (ut/cljs-env? env)
                                      (analyzer/macroexpand-1 {} node)
                                      (macroexpand-1 node))))
                recur)

            ((:dot-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-dot node))
                z/down z/right
                recur)

            :else
            (recur (z/next loc))))

        :else (recur (z/next loc))))))


;;; insert/remove d
(defn insert-d [form d-sym env]

  ;(println "INSERT-D" form d-sym env)
  (loop [loc (ut/sequential-zip form)]
    (let [node (z/node loc)]
      ;(ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;;; in case of (spy-first ...) (and more to come)
        ;(and (seq? node) (= `ms/skip (first node)))
        ;(recur (-> (z/down node)
        ;           z/right
        ;           z/down))

        ;; TODO: is it more efficient to remove the skips here
        ;; rather than taking another pass through the form?

        ;; in case of (skip ...)
        (and (seq? node) (= `ms/skip (first node)))
        (recur (ut/right-or-next loc))

        ;; in case of (o-skip ...)
        (and (seq? node)
             (= `ms/o-skip (first node)))
        (cond
          ;; <ex> (o-skip [(skip a) ...])
          (vector? (second node))
          (recur (-> loc z/down z/next z/down))

          ;; <ex> (o-skip (recur ...))
          :else
          (recur (-> loc z/down z/next z/down ut/right-or-next)))

        ;; TODO: handle lists that are just lists, not function calls


        ;; in case of (skip-outer ...)
        (and (seq? node)
             (= `ms/skip-outer (first node)))
        (let [inner-loc  (-> loc z/down z/right)
              inner-node (z/node inner-loc)]
          (cond
            (and (seq? inner-node)
                 (= `ms/skip (first inner-node)))
            ;; Recur once and let skip handle case
            (recur inner-loc)

            (seq? inner-node)
            (recur (-> inner-loc z/down ut/right-or-next))

            (vector? inner-node)
            (recur (-> inner-loc z/down))

            :else
            (recur (-> inner-loc ut/right-or-next))


            ;true (throw (ex-info "Pause" {}))
            ;; vector
            ;; map
            ;; form

            ))


        ;; in case that the first symbol is defn/defn-
        (and (seq? node)
             (symbol? (first node))
             (`#{defn defn-} (ut/ns-symbol (first node) env)))
        (recur (-> loc z/down z/next))

        ;;; in case the first symbol is ut/spy-first
        ;(and (seq? node)
        ;     (symbol? (first node))
        ;     (`#{ut/spy-first} (ut/ns-symbol (first node) env)))
        ;;; we need to z/replace of the next argument to spy-first with outer-skip
        ;;; then move onto skip-outer
        ;(recur (-> loc z/down z/next))

        #_(let [new-loc  (-> loc z/down z/next)
                new-node (z/node new-loc)]
            (if (and (seq? node)
                     (symbol? (first node)))
              (recur (-> new-loc z/down z/next))
              (recur (-> new-loc ut/right-or-next))
              ))

        ;; in case of the first symbol except defn/defn-/def

        ;; DC: why not def? where is that handled?
        (and (seq? node) (ifn? (first node)))
        (recur (-> (z/replace loc (concat [d-sym] [node]))
                   z/down z/right z/down ut/right-or-next))

        ;; |[1 2 (+ 3 4)]
        ;; |(d [1 2 (+ 3 4)])


        (vector? node)
        (recur (-> (z/replace loc (concat [d-sym] [node]))
                   z/down z/right z/down))


        ;; DC: We might also want to trace inside maps, especially for fx
        ;; in case of symbol, map, or set
        (or (symbol? node) (map? node) (set? node))
        (recur (-> (z/replace loc (concat [d-sym] [node]))
                   ut/right-or-next))

        :else
        (recur (z/next loc))))))

(defn debux-form? [sym]
  (contains? #{'debux.common.macro-specs/skip-outer
               'debux.common.macro-specs/skip
               'debux.common.macro-specs/o-skip
               'debux.common.util/spy-last
               'debux.common.util/spy-first
               'debux.common.util/spy-comp}
             sym))

(defmacro d [form]
  `(let [opts#   ~'+debux-dbg-opts+
         msg#    (:msg opts#)
         n#      (or (:n opts#) @ut/print-seq-length*)

         result# ~form
         result# (ut/take-n-if-seq n# result#)]
     (ut/send-trace! {:form '~(remove-d form 'debux.dbgn/d)
                      :result result#
                      :indent-level @ut/indent-level*})
     (ut/print-form-with-indent (ut/form-header '~(remove-d form 'debux.dbgn/d) msg#)
                                @ut/indent-level*)
     (ut/pprint-result-with-indent result# @ut/indent-level*)
     result#))


;;; remove skip
(defn remove-skip [form]
  (loop [loc (ut/sequential-zip form)]
    (let [node (z/node loc)]
      ;(ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;; in case of (skip ...)
        (and (seq? node)
             (= `ms/skip (first node)))
        (recur (-> (z/replace loc (second node))
                   ut/right-or-next))

        ;; in case of (o-skip ...)
        (and (seq? node)
             (= `ms/o-skip (first node)))
        (recur (-> (z/replace loc (second node))
                   z/next))

        ;; in case of (skip-outer ...)
        (and (seq? node)
             (= `ms/skip-outer (first node)))
        (recur (-> (z/replace loc (second node))))

        :else
        (recur (z/next loc))))))


;;; dbgn
(defmacro dbgn
  "DeBuG every Nested forms of a form.s"
  [form & [{:keys [condition] :as opts}]]
  ;(println "BEFORE" form opts)
  ;(println "FULLFORM" &form)
  `(let [~'+debux-dbg-opts+ ~(if (ut/cljs-env? &env)
                               (dissoc opts :style :js :once)
                               opts)
         condition# ~condition]
     (try
       (if (or (nil? condition#) condition#)
         (let [title# (str "\ndbgn: " (ut/truncate (pr-str '~form)) " =>")]
           ;; Send whole form to trace point
           (ut/send-form! '~(-> form (remove-d 'todomvc.dbgn/d) (ut/tidy-macroexpanded-form {})))
           (println title#)
           ;(println "FORM" '~form)
           ~(-> (if (ut/include-recur? form)
                  (sk/insert-o-skip-for-recur form &env)
                  form)
                (insert-skip &env)
                (insert-d 'debux.dbgn/d &env)
                remove-skip))
         ~form)
       (catch ~(if (ut/cljs-env? &env)
                 :default
                 Exception)
              ~'e (throw ~'e)))))

(defn spy [x]
  ;(zp/czprint x)
  x)

(defmacro mini-dbgn
  "DeBuG every Nested forms of a form.s"
  [form]
  `(do ~(-> (if (ut/include-recur? form)
            (sk/insert-o-skip-for-recur form &env)
            form)
          (insert-skip &env)
          (insert-d 'debux.dbgn/d &env)
          remove-skip)))


;; Two phase approach
;; add skips (+ macroexpansion)
;; add d's

;; macros create lots of code which we don't want to see
;; we want to output forms and form values at particular points, but not the rest of the stuff injected by the macros
;; Difficulty in two phase adding is that we do macroexpansion in first phase, so we have to annotate all macro code with skips.


;(conj :d)
;(conj [:a :b] :d)

;; We handle use of macros within macros then we macro-expand them before returning them out.
;; pre-emptive macroexpansion




;(dbgn (-> {:a 1}
;          (assoc :a 3)
;          frequencies))
;
;(dbgn (-> :a (cons '(1 2 3))))

;(defn c-kw []
;  :c)
;
;(dbgn (some-> [:a :b (c-kw)]
;              (conj :d)
;              (distinct)))
;
;(dbgn (->> [:a :b (c-kw)]
;             (cons :d)
;             (distinct)))

#_(defn my-fun [a b c]
    (dbgn (+ a b c
             (->> (range a b)
                  (map (fn [x] (* x x)))
                  (filter even?)
                  (take a)
                  (reduce +)))))

;(reduce + (take a (filter even? (map (fn [x] (* x x)) (range a b)))))

