(ns debux.core
  (:require [debux.dbg :as dbg]
            [debux.dbgn :as dbgn]
            [debux.macro-types :as mt]
            [debux.common.util :as ut]))

(def reset-indent-level! ut/reset-indent-level!)
(def set-print-seq-length! ut/set-print-seq-length!)

;;; debugging APIs
(defmacro dbg [form & opts]
  (let [opts' (ut/parse-opts opts)]
    `(dbg/dbg ~form ~opts')))

(defmacro dbgn [form & opts]
  (let [opts' (ut/parse-opts opts)]
    `(dbgn/dbgn ~form ~opts')))


;;; macro registering APIs
(defmacro register-macros! [macro-type symbols]
  `(mt/register-macros! ~macro-type ~symbols))

(defmacro show-macros
  ([] `(mt/show-macros))
  ([macro-type] `(mt/show-macros ~macro-type)))

;
;(dbg (-> {:a 1}
;         (assoc :a 3)
;         (frequencies)))
;
;(declare f)
;
;(dbg (-> {:a 1}
;         (ut/spy-first (skip '{:a 1}))
;         (assoc :a (f 3))
;         (ut/spy-first (skip '(assoc :a (f 3))))
;         frequencies
;         (ut/spy-first (skip 'frequencies))))
;
;;; Need to skip the quoted forms too
;
;(ut/spy-first
;  (frequencies
;    (ut/spy-first
;      (assoc (ut/spy-first {:a 1} (skip '{:a 1}))
;        :a
;        (f 3))
;      (skip '(assoc :a (f 3)))))
;  (skip 'frequencies))
;
;;; spy-first: don't add d to first parameter (assoc :47), but recurse into it and trace parameters of assoc.
;
;
;
;(debux.dbgn/d
;  (debux.dbgn/d
;    (assoc (debux.dbgn/d {:a 1}
;                         '{:a 1})
;      :a 3)
;    '(assoc :a 3))
;  '(frequencies))




;(dbgn (-> {:a 1}
;          (assoc :a 3)
;          (frequencies)))
;
;
;(debux.dbgn/d
;   (-> (debux.dbgn/d {:a 1})
;      (debux.dbgn/d (assoc :a 3))
;      (debux.dbgn/d (frequencies))))
;
