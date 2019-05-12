(ns fexpr-test.core-test
  (:require [clojure.test :refer :all]
            [fexpr-test.core :refer :all]))

(deftest basic-stuff
  (testing "parsing and evaling basic stuff"
    (is (= "7"
           (display (eval! '7 Empty-env))))
    (is (= "Nil"
           (display (eval! '*nil Ground-env))))
    (is (= "[:undefined \"unbound-symbol\"]"
           (display (eval! 'unbound-symbol Empty-env))))
    (is (= "[:undefined \"unbound-symbol\"]"
           (display (eval! 'unbound-symbol Ground-env))))
    (is (= "operative-if"
           (display (eval! '&if Ground-env))))))

(deftest applicatives
  (testing "lambda works?"
    (is (= "7"
           (display (eval! '((&lambda (x) x) 7)
                           Ground-env))))))

(deftest bad-lookups
  (testing "invalid combiner"
    (is (= "[:invalid-combiner [:undefined \"f\"]]"
           (display (eval! '(f 11)
                           Ground-env))))))

(deftest complete-example-from-article
  (testing "here we go"
    (is (= "42"
           (display (eval! '((&lambda (x) (&if (eq? x *inert) answer x))
                             (&define! answer 42))
                           Ground-env))))))
