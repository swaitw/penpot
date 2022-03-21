;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.export
  "The main entry point for UI part needed by the exporter."
  (:require
   [app.common.logging :as log]
   [app.common.spec :as us]
   [app.common.uri :as u]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.store :as st]
   [app.main.ui.render :as render]
   [app.util.dom :as dom]
   [app.util.globals :as glob]
   [clojure.spec.alpha :as s]
   [rumext.alpha :as mf]))

(log/initialize!)
(log/set-level! :root :warn)
(log/set-level! :app :info)

(declare reinit)
(declare ^:private render-object)
(declare ^:private parse-params)
;; http://localhost:3449/export.html?file-id=98892200-a929-11ec-beb1-40a31b8f5a01&page-id=98892201-a929-11ec-beb1-40a31b8f5a01&object-id=9a7588b0-a929-11ec-abe4-2383f8230d8f&page=render-object

(log/info :hint "Welcome to penpot (Export)" :version (:full @cf/version) :public-uri (str cf/public-uri))


(defn init-ui
  []
  (when-let [params (parse-params (.-href ^js glob/location))]
    (when-let [component (case (:page params)
                           "render-object" (render-object params)
                           nil)]
      (js/console.log component)
      (mf/mount  component (dom/get-element "app")))))

(defn ^:export init
  []
  (init-ui))

(defn reinit
  []
  (mf/unmount (dom/get-element "app"))
  (init-ui))

(defn ^:dev/after-load after-load
  []
  (reinit))

(defn- parse-params
  [href]
  (some-> href u/uri :query u/query-string->map))

(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::render-text ::us/boolean)

(s/def ::render-object-params
  (s/keys :req-un [::file-id ::page-id ::object-id]
          :opt-un [::render-text]))

(defn- render-object
  [params]
  (let [{:keys [page-id file-id object-id render-texts]} (us/conform ::render-object-params params)]
    (mf/html
     [:& render/render-object
      {:file-id file-id
       :page-id page-id
       :object-id object-id
       :render-texts? (and (some? render-texts) (= render-texts "true"))}])))




