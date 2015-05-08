(ns puppetlabs.services.master.master-core-test
  (:require [clojure.test :refer :all]
            [puppetlabs.services.master.master-core :refer :all]
            [ring.mock.request :as mock]
            [schema.test :as schema-test]
            [puppetlabs.comidi :as comidi]))

(use-fixtures :once schema-test/validate-schemas)

(defn build-ring-handler
  [request-handler puppet-version]
  (-> (root-routes request-handler)
      (comidi/routes->handler)
      (wrap-middleware puppet-version)))

(deftest test-master-routes
  (let [handler     (fn ([req] {:request req}))
        app         (build-ring-handler handler "1.2.3")
        request     (fn r ([path] (r :get path))
                          ([method path] (app (mock/request method path))))]
    (is (nil? (request "/v2.0/foo")))
    (is (= 200 (:status (request "/v2.0/environments"))))
    (is (nil? (request "/foo")))
    (is (nil? (request "/foo/bar")))
    (doseq [[method paths]
            {:get ["catalog"
                   "node"
                   "facts"
                   "file_content"
                   "file_metadatas"
                   "file_metadata"
                   "file_bucket_file"
                   "resource_type"
                   "resource_types"
                   "status"
                   "facts_search"]
             :post ["catalog"]
             :put ["file_bucket_file"
                   "report"]
             :head ["file_bucket_file"]}
            path paths]
      (let [resp (request method (str "/foo/" path "/bar"))]
        (is (= 200 (:status resp))
            (str "Did not get 200 for method: "
                 method
                 ", path: "
                 path))))))

(deftest file-bucket-file
  (testing (str "that the content-type in the ring request is replaced with "
                "application/octet-stream for a file_bucket_file put request")
    (let [handler     (fn ([req] {:request req}))
          app         (build-ring-handler handler "1.2.3")
          resp        (app {:request-method :put
                            :content-type   "text/plain"
                            :uri            "/foo/file_bucket_file/bar"})]
      (is (= "application/octet-stream"
             (get-in resp [:request :content-type]))))))
