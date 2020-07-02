(ns day8.re-frame.debux.dbgn
  (:require [clojure.zip :as z]
            [cljs.analyzer :as analyzer]
            [day8.re-frame.debux.common.macro-specs :as ms]
            [day8.re-frame.debux.common.skip :as sk]
            [day8.re-frame.debux.common.util :as ut :refer [remove-d]]
            [day8.re-frame.debux.cs.macro-types :as mt]
            [re-frame.trace :as trace]))

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

;; 3. after insert-trace
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
  @mt/macro-types*)

;;; insert skip
(defn insert-skip
  "Marks the form to skip."
  [form env]
  ; (println "INSERT-SKIP" form env)
  ; (println "SEQ ZIP" (z/node (ut/sequential-zip form)))
  (loop [loc (ut/sequential-zip form)]
    (let [node (z/node loc)]
      ; (ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;; in case of (skip ...)
        (and (seq? node) (= `ms/skip (first node)))
        (recur (ut/right-or-next loc))

        (and (seq? node) (symbol? (first node)))
        (let [sym (ut/ns-symbol (first node) env)]
          ; (println "NODE" node "SYM" sym)
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

            ((:thread-first-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-thread-first node))
                z/next
                recur)

            ((:thread-last-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-thread-last node))
                z/next
                recur)

            ((:cond-first-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-cond-first node))
                z/next
                recur)

            ((:cond-last-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-cond-last node))
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

            ((:dot-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-dot node))
                z/down z/right
                recur)

            ((:dot-dot-type (macro-types env)) sym)
            (-> (z/replace loc (sk/insert-skip-in-dot-dot node))
                z/down z/right
                recur)

            :else
            (recur (z/next loc))))

        :else (recur (z/next loc))))))

(defn depth
  "Calculate how far we are inside the zipper, by ascending straight up
  until we can't get any higher."
  ;;  There is probably a smarter way to do
  ;; this than checking for nil, but I'm not sure what it is.
  [loc]
  (loop [loc loc
         depth -1]
    (if (nil? loc)
      depth
      (recur (z/up loc)
             (inc depth)))))

(defn debux-symbol? [sym]
  (contains? #{'day8.re-frame.debux.dbgn/trace
               'day8.re-frame.debux.common.util/spy-first
               'day8.re-frame.debux.common.util/spy-last
               'day8.re-frame.debux.common.util/spy-comp
               'day8.re-frame.debux.common.macro-specs/skip-outer
               'day8.re-frame.debux.common.macro-specs/skip
               'day8.re-frame.debux.common.macro-specs/o-skip}
             sym))

(defn real-depth
  "Calculate how far we are inside the zipper, ignoring synthetic debux forms,
   by ascending straight up until we can't get any higher."
  ;;  There is probably a smarter way to do
  ;; this than checking for nil, but I'm not sure what it is.
  [loc]
  (try
    (if (and (sequential? (z/node loc))
             (debux-symbol? (first (z/node loc))))
      nil
      (loop [loc   loc
             depth -1]
        (if (nil? loc)
          depth
          (let [node (z/node loc)]
            (recur (z/up loc)
                   (if (and (sequential? node)
                            (debux-symbol? (first node)))
                     depth
                     (inc depth)))))))
    (catch java.lang.NullPointerException e -1)))  ;; not a zipper

;;; insert/remove d
(defn insert-trace [form d-sym env]

  ; (println "INSERT-TRACE" (prn-str form))
  (loop [loc (ut/sequential-zip form)
         indent 0]
    (let [node (z/node loc)
          #_ #_ indent (real-depth loc)]
    ;   (println "node" node)
      (cond
        (z/end? loc) (z/root loc)

        ;;; in case of (spy-first ...) (and more to come)
        ;(and (seq? node) (= `ms/skip (first node)))
        ;(recur (-> (z/down node)
        ;           z/right
        ;           z/down))

        ;; TODO: is it more efficient to remove the skips here
        ;; rather than taking another pass through the form?

        ;; in case of (.. skip ...)
        (= ::ms/skip node)
        (recur (ut/right-or-next loc) indent)

        ;; in case of (skip ...)
        (and (seq? node) (= `ms/skip (first node)))
        (recur (ut/right-or-next loc) indent)

        ;; in case of (o-skip ...)
        (and (seq? node)
             (= `ms/o-skip (first node)))
        (cond
          ;; <ex> (o-skip [(skip a) ...])
          (vector? (second node))
          (recur (-> loc z/down z/next z/down) indent)

          ;; <ex> (o-skip (recur ...))
          :else
          (recur (-> loc z/down z/next z/down ut/right-or-next) indent))

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
            (recur inner-loc indent)

            (seq? inner-node)
            (recur (-> inner-loc z/down ut/right-or-next) indent)

            (vector? inner-node)
            (recur (-> inner-loc z/down) indent)

            :else
            (recur (-> inner-loc ut/right-or-next) indent)


            ;true (throw (ex-info "Pause" {}))
            ;; vector
            ;; map
            ;; form

            ))


        ;; in case that the first symbol is defn/defn-
        (and (seq? node)
             (symbol? (first node))
             (`#{defn defn-} (ut/ns-symbol (first node) env)))
        (recur (-> loc z/down z/next) indent)

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
        (recur (-> (z/replace loc (concat [d-sym (real-depth loc) node ]))
                   z/down z/right z/right z/down ut/right-or-next)
               (inc indent))

        ;; |[1 2 (+ 3 4)]
        ;; |(d [1 2 (+ 3 4)])


        (vector? node)
        (recur (-> (z/replace loc (concat [d-sym (real-depth loc) node]))
                   z/down z/right z/right z/down)
               indent)

        (map? node)
        (recur (-> (z/replace loc (concat [d-sym (real-depth loc) node]))
                   z/down z/right z/right z/down)
               indent)

        (= node `day8.re-frame.debux.common.macro-specs/indent)
        ;; TODO: does this real-depth need an inc/dec to bring it into line with the d?
        (recur (z/replace loc (real-depth loc)) indent)

        ;; DC: We might also want to trace inside maps, especially for fx
        ;; in case of symbol, map, or set
        (or (symbol? node) (map? node) (set? node))
        (recur (-> (z/replace loc (concat [d-sym (real-depth loc) node]))
                   ;; We're not zipping down inside the node further, so we don't need to add a
                   ;; second z/right like we do in the case of a vector or ifn? node above.
                   ut/right-or-next)
               indent)

        :else
        (recur (z/next loc) indent)))))

(defmulti trace*
  (fn [& args]
      ; (println "trace*" args)
      (cond
        (= 2 (count args))  :trace
        (= java.lang.Long
           (-> args
               second
               type))     :trace->
        :else :trace->>)))

(defmethod trace* :trace
  [indent form]
;   (println "TRACE" indent form)
  (let [org-form (-> form
                     (remove-d 'day8.re-frame.debux.dbgn/trace))]
    `(let [opts#   ~'+debux-dbg-opts+
           result# ~form]
       (ut/send-trace! {:form '~org-form
                        :result result#
                        :indent-level ~indent})
       result#)))


(defmethod trace* :trace->
  [f indent form]
  ; (println "TRACE->" indent f form)
   (let [org-form (-> form
                      (remove-d 'day8.re-frame.debux.dbgn/trace))]
    `(let [opts#   ~'+debux-dbg-opts+
           result# (-> ~f ~form)]
       (ut/send-trace! {:form '~org-form
                        :result result#
                        :indent-level ~indent})
       result#)))

(defmethod trace* :trace->>
  [indent form f]
  ; (println "TRACE->>" indent f form)
   (let [org-form (-> form
                      (remove-d 'day8.re-frame.debux.dbgn/trace))]
    `(let [opts#   ~'+debux-dbg-opts+
           result# (->> ~f ~form)]
       (ut/send-trace! {:form '~org-form
                        :result result#
                        :indent-level ~indent})
       result#)))

(defmacro trace [& args]
  (apply trace* args))


(defn spy [x]
  ;(zprint.core/czprint x)
  x)

;;; remove skip
(defn remove-skip [form]
;   (println "REMOVE-SKIP")
  (loop [loc (ut/sequential-zip form)]
    (let [node (z/node loc)]
      ;(ut/d node)
      (cond
        (z/end? loc) (z/root loc)

        ;; in case of (.. skip ...)
        (= ::ms/skip-place node)
        (recur (-> (z/remove loc)
                  ut/right-or-next))

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
  [form & [opts]]
  ; (println "BEFORE" form opts)
  ; (println "FULLFORM" &form)
  `(let [~'+debux-dbg-opts+ ~(if (ut/cljs-env? &env)
                               (dissoc opts :style :js :once)
                               opts)]
     (try
       ;; Send whole form to trace point
       (ut/send-form! '~(-> form (ut/tidy-macroexpanded-form {})))
       ~(-> (if (ut/include-recur? form)
              (sk/insert-o-skip-for-recur form &env)
              form)
            (insert-skip &env)
            (insert-trace 'day8.re-frame.debux.dbgn/trace &env)
            remove-skip)
       ;; TODO: can we remove try/catch too?
       (catch ~(if (ut/cljs-env? &env)
                 :default
                 Exception)
              ~'e (throw ~'e)))))

(defmacro mini-dbgn
  "DeBuG every Nested forms of a form.s"
  [form]
  `(do ~(-> (if (ut/include-recur? form)
            (sk/insert-o-skip-for-recur form &env)
            form)
          (insert-skip &env)
          (insert-trace 'day8.re-frame.debux.dbgn/trace &env)
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

