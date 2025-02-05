(ns puppetlabs.puppetserver.ruby.http-client-test
  (:import (org.jruby.embed EvalFailedException)
           (org.apache.http ProtocolException ConnectionClosedException)
           (com.puppetlabs.ssl_utils SSLUtils)
           (javax.net.ssl SSLHandshakeException SSLException)
           (java.util HashMap)
           (java.io IOException)
           (java.util.zip GZIPInputStream))
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]
            [puppetlabs.trapperkeeper.testutils.webserver :as jetty9]
            [puppetlabs.services.jruby.jruby-puppet-testutils :as jruby-puppet-testutils]
            [ring.middleware.basic-authentication :as auth]
            [schema.core :as schema]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-puppet-core]
            [puppetlabs.services.jruby-pool-manager.jruby-core :as jruby-core]
            [puppetlabs.services.jruby-pool-manager.impl.jruby-pool-manager-core :as jruby-impl-core]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]))

;; NOTE: this namespace is pretty disgusting.  It'd be much nicer to test this
;; ruby code via ruby spec tests, but since we need to stand up a webserver to
;; make requests against, it's way easier to do it with clojure and tk-j9.

(defn test-resource
  [filename]
  (str "./dev-resources/puppetlabs/puppetserver/ruby/http_client_test/" filename))

(defn ring-app
  [_req]
  {:status 200
   :body "hi"})

(defn ring-app-alternate
  [_req]
  {:status 200
   :body "bye"})

(defn ring-app-connection-closed
  [req]
  {:status 200
   :body (str "The Connection header has value "
              ((:headers req) "connection"))})

(defn ring-app-decompressing-gzipped-request
  [req]
  {:status 200
   :body (-> req
             :body
             (GZIPInputStream.)
             slurp)})

(defn authenticated? [name pass]
  (and (= name "foo")
       (= pass "bar")))

(def ring-app-with-auth
  (-> ring-app
      (auth/wrap-basic-authentication authenticated?)))

(defn ssl-connection-exception?
  [ex]
  (or
    (instance? ConnectionClosedException ex)
    (instance? SSLHandshakeException ex)
    (and (instance? SSLException ex)
         (= "Received fatal alert: handshake_failure" (. ex getMessage)))
    (and (instance? IOException ex)
         (= "Connection reset by peer" (.getMessage ex)))))

(defn get-http-client-settings
  [options]
  (let [result (HashMap.)]
    (when (contains? options :ssl-protocols)
      (.put result "ssl_protocols" (into-array String (:ssl-protocols options))))
    (when (contains? options :cipher-suites)
      (.put result "cipher_suites" (into-array String (:cipher-suites options))))
    result))

(def ca-pem (test-resource "ca.pem"))
(def cert-pem (test-resource "localhost_cert.pem"))
(def privkey-pem (test-resource "localhost_key.pem"))

(schema/defn ^:always-validate
 jruby-config :- jruby-schemas/JRubyConfig
  "Create a JRubyConfig for testing. The optional map argument `options` may
  contain a map, which, if present, will be merged into the final JRubyConfig
  map.  (This function differs from `jruby-tk-config` in that it returns a map
  that complies with the JRubyConfig schema, which differs slightly from the raw
  format that would be read from config files on disk.)"
  []
  (jruby-core/initialize-config
   {:ruby-load-path (jruby-puppet-core/managed-load-path
                     jruby-puppet-testutils/ruby-load-path)
    :gem-home jruby-puppet-testutils/gem-home
    :gem-path jruby-puppet-testutils/gem-path}))

(defn create-scripting-container
  "A JRuby ScriptingContainer with an instance of 'Puppet::Server::HttpClient'
  assigned to a variable 'c'.  The ScriptingContainer was created
  with LocalVariableBehavior.PERSISTENT so that the HTTP client instance
  will persistent across calls to 'runScriptlet' in tests."
  []
  (let [jruby-config (jruby-config)
        scripting-container (-> (jruby-impl-core/create-pool jruby-config)
                                (jruby-core/borrow-from-pool :http-client-test [])
                                :scripting-container)]
    (doto scripting-container,
      (.runScriptlet "require 'puppet'")
      (.runScriptlet (format "Puppet[:hostcert] = '%s'" cert-pem))
      (.runScriptlet (format "Puppet[:hostprivkey] = '%s'" privkey-pem))
      (.runScriptlet (format "Puppet[:localcacert] = '%s'" ca-pem)))
    scripting-container))

(defn create-http-client
  [scripting-container options]
  (.runScriptlet scripting-container "require 'puppet/server/http_client'")
  (let [http-client-settings (get-http-client-settings
                              (select-keys options [:ssl-protocols
                                                    :cipher-suites]))
        http-client-class (.runScriptlet scripting-container
                                         "Puppet::Server::HttpClient")]
    (.callMethodWithArgArray scripting-container
                             http-client-class
                             "initialize_settings"
                             (into-array Object [http-client-settings])
                             Object)
    (.runScriptlet scripting-container "$c = Puppet::Server::HttpClient.new;")))

(defn terminate-http-client
  [scripting-container]
  (.runScriptlet scripting-container "$c = nil")
  ;; This step purges the '@ssl_context' which may have been used with an http
  ;; client call.  This is necessary to ensure that any subsequent http client
  ;; call utilizes a new SSL context rather than unintentionally using some
  ;; cached state - e.g., ssl parameters from prior connection attempt.
  (.runScriptlet scripting-container
                 "Puppet::Server::Config.instance_variable_set('@ssl_context', nil)")
  (.runScriptlet scripting-container "Puppet::Server::HttpClient.terminate"))

(defn terminate-scripting-container
  [scripting-container]
  (.terminate scripting-container))

(def ^:dynamic ^:private *scripting-container* nil)

(defmacro with-scripting-container
  [scripting-container & body]
  `(let [~scripting-container (or *scripting-container*
                                  (create-scripting-container))]
     (try
       ~@body
       (finally
         (when-not *scripting-container*
           (terminate-scripting-container ~scripting-container))))))

(defmacro with-http-client
  [scripting-container options & body]
  `(try
     (create-http-client ~scripting-container ~options)
     ~@body
     (finally
       (terminate-http-client ~scripting-container))))

(defn http-client-scripting-container-fixture
  [test-fn]
  (with-scripting-container sc
    (binding [*scripting-container* sc]
      (test-fn))))

(use-fixtures :once http-client-scripting-container-fixture)

(deftest test-ruby-http-client
  (jetty9/with-test-webserver ring-app port
    (with-scripting-container sc
      (with-http-client sc {}
        (let [url (str "http://localhost:" port)]
          (testing "HTTP GET"
            (is (= "hi" (.runScriptlet sc (format "$c.get(URI('%s')).body" url)))))
          (testing "HTTP POST"
            (is (= "hi" (.runScriptlet sc (format "$c.post(URI('%s'), 'foo').body" url))))))))))

(deftest http-escaped-urls-test
  (jetty9/with-test-webserver ring-app port
    (with-scripting-container sc
      (with-http-client sc {}
        (let [url (str "http://localhost:" port)]
          (testing "HTTP GET"
            (.runScriptlet sc (str "$response = $c.get(URI('" url "/a%20b%3Fc'), params: { foo: 'bar' })"))
            (is (= 200 (.runScriptlet sc "$response.code")))
            (is (= (str url "/a%20b%3Fc?foo=bar") (.runScriptlet sc "$response.url.to_s")))))))))

(deftest http-basic-auth
  (jetty9/with-test-webserver ring-app-with-auth port
    (with-scripting-container sc
      (with-http-client sc {}
        (let [url (str "http://localhost:" port)]
          (testing "no credentials"
            (.runScriptlet sc (format "$response = $c.post(URI('%s'), 'foo')" url))
            (is (= 401 (.runScriptlet sc "$response.code")))
            (is (= "Unauthorized" (.runScriptlet sc "$response.reason")))
            (is (= "access denied" (.runScriptlet sc "$response.body"))))

          (testing "valid credentials"
            (let [auth "{ :basic_auth => { :user => 'foo', :password => 'bar' }}"]
              (.runScriptlet sc (format "$response = $c.post(URI('%s'), 'foo', options: %s)" url auth)))
            (is (= 200 (.runScriptlet sc "$response.code")))
            (is (= "hi" (.runScriptlet sc "$response.body"))))

          (testing "invalid credentials"
            (let [auth "{ :basic_auth => { :user => 'foo', :password => 'baz' }}"]
              (.runScriptlet sc (format "$response = $c.post(URI('%s'), 'foo', options: %s)" url auth)))
            (is (= 401 (.runScriptlet sc "$response.code")))
            (is (= "access denied" (.runScriptlet sc "$response.body")))))))))

(deftest http-compressed-requests
  (jetty9/with-test-webserver ring-app-decompressing-gzipped-request port
    (with-scripting-container sc
      (with-http-client sc {}
        (let [url (str "http://localhost:" port)]
          (testing "GZIP compression format"
            (let [compress "{ :compress => :gzip }"
                  body "howdy"]
              (.runScriptlet sc (format "$response = $c.post(URI('%s'), '%s', options: %s)"
                                        url
                                        body
                                        compress))
              (is (= 200 (.runScriptlet sc "$response.code")))
              (is (= body (.runScriptlet sc "$response.body")))))

          (testing "invalid compression format"
            (let [compress "{ :compress => :bunk }"]
              (is (= "Unsupported compression specified for request: bunk"
                     (.runScriptlet
                      sc
                      (str "begin;"
                           (format "  $response = $c.post(URI('%s'), 'foo', options: %s);" url compress)
                           "  'No error raised from post';"
                           "rescue ArgumentError => e;"
                           "  e.message;"
                           "end")))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; SSL Tests

(defmacro with-webserver-with-protocols
  [protocols cipher-suites & body]
  `(jetty9/with-test-webserver-and-config ring-app port#
    (merge {:ssl-host    "localhost"
            :ssl-port    10080
            :ssl-ca-cert ca-pem
            :ssl-cert    cert-pem
            :ssl-key     privkey-pem}
           (if ~protocols
             {:ssl-protocols ~protocols})
           (if ~cipher-suites
             {:cipher-suites ~cipher-suites}))
    ~@body))

(defn raise-caught-http-error
  "Returns a Ruby script string that executes `script', rescues the expected
   Puppet::Server::HttpClientError, and raises the nested exception for testing
   the underlying cause."
  [script]
  (str "begin;"
       script
       ";rescue Puppet::Server::HttpClientError => e;"
       "  raise e.cause.getCause;"
       "end"))

(deftest https-tls-defaults
  (testing "requests fail without an SSL client"
    (with-webserver-with-protocols nil nil
      (with-scripting-container sc
        (with-http-client sc {}
          (let [url (str "http://localhost:10080")]
            (logutils/with-test-logging
             (let [ex-class (if (SSLUtils/isFIPS)
                              ConnectionClosedException
                              ProtocolException)]
               (try
                 (.runScriptlet sc (raise-caught-http-error (format "$c.get(URI('%s'))" url)))
                 (is false "Expected HTTP connection to HTTPS port to fail")
                 (catch EvalFailedException e
                   (is (instance? ex-class (.getCause e))))))))))))

  (testing "Can connect via TLSv1.3 by default"
    (with-webserver-with-protocols ["TLSv1.3"] nil
      (with-scripting-container sc
        (with-http-client sc {:cipher-suites ["TLS_AES_256_GCM_SHA384" "TLS_AES_128_GCM_SHA256"]}
          (let [url (str "https://localhost:10080")]
            (.runScriptlet sc (format "$response = $c.get(URI('%s'))" url))
            (is (= 200 (.runScriptlet sc "$response.code")))
            (is (= "hi" (.runScriptlet sc "$response.body"))))))))

  (testing "Can connect via TLSv1.2 by default"
    (with-webserver-with-protocols ["TLSv1.2"] nil
      (with-scripting-container sc
        (with-http-client sc {}
          (let [url (str "https://localhost:10080")]
            (.runScriptlet sc (format "$response = $c.get(URI('%s'))" url))
            (is (= 200 (.runScriptlet sc "$response.code")))
            (is (= "hi" (.runScriptlet sc "$response.body")))))))))

(deftest clients-persist
  (testing "client persists when making HTTP requests"
    (logutils/with-test-logging
      (jetty9/with-test-webserver ring-app port
        (with-scripting-container sc
          (with-http-client sc {}
            (let [url (str "http://localhost:" port)
                  client1 (.runScriptlet sc (format "$c.get(URI('%s')); $c.class.client" url))
                  client2 (.runScriptlet sc (format "$c.post(URI('%s'), 'foo'); $c.class.client" url))]
              (is (= client1 client2))))))))
  (testing "all instances of HttpClient have the same underlying client object"
    (logutils/with-test-logging
      (jetty9/with-test-webserver ring-app port
        (with-scripting-container sc
          (with-http-client sc {}
            (let [client1 (.runScriptlet sc "$c.class.client")
                  client2 (.runScriptlet sc (str "$c2 = Puppet::Server::HttpClient.new;"
                                                 "$c2.class.client"))]
              (is (= client1 client2)))))))))

(deftest connections-closed
  (testing "connection header always set to close on get"
    (logutils/with-test-logging
      (jetty9/with-test-webserver ring-app-connection-closed port
        (with-scripting-container sc
          (with-http-client sc {}
            (let [url (str "http://localhost:" port)]
              (is (= "The Connection header has value close"
                     (.runScriptlet sc (format "$c.get(URI('%s')).body" url))))))))))
  (testing "connection header always set to close on post"
    (logutils/with-test-logging
      (jetty9/with-test-webserver ring-app-connection-closed port
        (with-scripting-container sc
          (with-http-client sc {}
            (let [url (str "http://localhost:" port)]
              (is (= "The Connection header has value close"
                     (.runScriptlet sc (format "$c.post(URI('%s'), 'foo').body" url))))))))))
  (testing "client's terminate function closes the client"
    (logutils/with-test-logging
      (jetty9/with-test-webserver ring-app-connection-closed port
        (with-scripting-container sc
          (with-http-client sc {}
            (let [url (str "http://localhost:" port)]
              (.runScriptlet sc (format "$response = $c.get(URI('%s'))" url))
              (is (= 200 (.runScriptlet sc "$response.code")))
              (.runScriptlet sc "$c.class.terminate")
              (try
                (.runScriptlet sc (format "$response = $c.get(URI('%s'))" url))
                (catch EvalFailedException e
                  (let [wrapped-exception (.getCause e)
                        message (.getMessage e)]
                    (is (instance? IllegalStateException wrapped-exception))
                    (is (re-find #"Request cannot be executed; I/O reactor status: STOPPED" message))))))))))))

(deftest http-and-https
  (testing "can make http calls after https calls without a new scripting container"
    (logutils/with-test-logging
      (jetty9/with-test-webserver ring-app-alternate port
        (with-webserver-with-protocols nil nil
          (with-scripting-container sc
            (with-http-client sc {}
              (let [url (str "https://localhost:10080")]
                (.runScriptlet sc (format "$response = $c.get(URI('%s'))" url))
                (is (= 200 (.runScriptlet sc "$response.code")))
                (is (= "hi" (.runScriptlet sc "$response.body")))
                (.runScriptlet sc (str "$c = Puppet::Server::HttpClient.new;"
                                       (format "$response = $c.get(URI('http://localhost:%s'))" port)))
                (is (= 200 (.runScriptlet sc "$response.code")))
                (is (= "bye" (.runScriptlet sc "$response.body"))))))))))

  (testing "can make https calls after http calls without a new scripting container"
    (logutils/with-test-logging
      (jetty9/with-test-webserver ring-app-alternate port
        (with-webserver-with-protocols nil nil
          (with-scripting-container sc
            (with-http-client sc {}
              (.runScriptlet sc (format "$response = $c.get(URI('http://localhost:%s'))" port))
              (is (= 200 (.runScriptlet sc "$response.code")))
              (is (= "bye" (.runScriptlet sc "$response.body")))
              (.runScriptlet sc (str "$c = Puppet::Server::HttpClient.new;"
                                     "$response = $c.get(URI('https://localhost:10080/'))"))
              (is (= 200 (.runScriptlet sc "$response.code")))
              (is (= "hi" (.runScriptlet sc "$response.body"))))))))))
