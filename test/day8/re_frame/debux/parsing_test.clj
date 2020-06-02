(ns day8.re-frame.debux.parsing-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]))

(defn all-indexes [s value]
  (loop [start   0
         indexes []]
    (if-some [i (str/index-of s value start)]
      (recur (inc i) (conj indexes i))
      indexes)))

(deftest all-indexes-test
  (is (= [] (all-indexes "abcd" "e")))
  (is (= [3] (all-indexes "abcd" "d")))
  (is (= [0 4 5 7] (all-indexes "dabcdd d" "d"))))

(defn all-matches [re s]
  (let [m (re-matcher re s)]
    (loop [ms []]
      (if (re-find m)
        (recur (conj ms [(.start m 2) (.end m 2) (.group m 2) (.groupCount m) (re-groups m)]))
        ms))))

(defn find-bounds [form search-form]
  (let [search-str (pr-str search-form)
        form-str   (pr-str form)
        [start end] (first (all-matches (re-pattern (format "(\\s|\\(|\\[|\\{)(\\Q%s\\E)" search-str))
                                        form-str))]
    [start end]))

(defn highlight-repr [form search-form]
  (let [form-str  (pr-str form)
        [start end] (find-bounds form search-form)
        before    (subs form-str 0 start)
        highlight (subs form-str start end)
        after     (subs form-str end)
        ]
    (str before "|" highlight "|" after)))

(deftest simple-find-test
  (is (= (highlight-repr '(let [new-w weeks]
                            new-w)
                         'weeks)
         "(let [new-w |weeks|] new-w)"))
  (is (= (highlight-repr '(inc x)
                         'x)
         "(inc |x|)")))

(deftest partial-match-find-test
  (let [form '(let [default-weeks 5
                    new-w         weeks])]
    (is (= (highlight-repr form 'weeks)
           "(let [default-weeks 5 new-w |weeks|])"))
    (is (= (highlight-repr form 'default-weeks)
           "(let [|default-weeks| 5 new-w weeks])"))))
