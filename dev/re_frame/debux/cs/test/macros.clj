(ns day8.re-frame.debux.cs.test.macros)

(defmacro my-let [bindings & body]
  `(let ~bindings ~@body))
