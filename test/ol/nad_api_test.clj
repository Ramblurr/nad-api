(ns ol.nad-api-test
  (:require
   [ol.nad-api :as sut]
   [clojure.test :refer [deftest is]]))

(deftest handler-test
  (is (=
       {:body "Hello world" :headers {"content-type" "text/html"} :status 200}
       (sut/handler {:uri "/" :request-method :get}))))
