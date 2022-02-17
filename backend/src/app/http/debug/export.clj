;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.http.debug.export
  "A custom, size and performance optimized export format."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.common.logging :as l]
   [app.media :as media]
   [app.config :as cf]
   [app.db :as db]
   [app.util.blob :as blob]
   [app.tasks.file-media-gc :as tfm]
   [app.rpc.mutations.files :as m.files]
   [app.rpc.queries.profile :as profile]
   [app.util.time :as dt]
   [app.storage :as sto]
   [clojure.java.io :as io]
   [cuerdas.core :as str]
   [datoteka.core :as fs]
   [ring.core.protocols :as rp]
   [fipp.edn :as fpp]
   [yetti.adapter :as yt])
  (:import
   java.io.DataOutputStream
   java.io.DataInputStream
   java.io.InputStream
   java.io.OutputStream
   java.io.BufferedOutputStream
   java.io.BufferedInputStream
   org.apache.commons.io.IOUtils
   com.github.luben.zstd.ZstdOutputStream
   org.apache.commons.io.input.BoundedInputStream))

(def ^:const buffer-size (:http/output-buffer-size yt/base-defaults))

(defn buffered-output-stream
  "Returns a buffered output stream that ignores flush calls. This is
  needed because transit-java calls flush very aggresivelly on each
  object write."
  [^java.io.OutputStream os ^long chunk-size]
  (proxy [java.io.BufferedOutputStream] [os (int chunk-size)]
    ;; Explicitly do not forward flush
    (flush [])
    (close []
      (proxy-super flush)
      (proxy-super close))))

(defn retrieve-file-media
  [pool ids]
  (with-open [conn (db/open pool)]
    (let [sql "select * from file_media_object where id = ANY(?)"]
      (db/exec! conn [sql (db/create-array conn "uuid" ids)]))))

(defn write-export!
  [{:keys [pool storage file-id]} ^DataOutputStream ostream]
  (let [data    (some-> (db/get-by-id pool :file file-id) :data)
        data'   (blob/decode data)
        storage (media/configure-assets-storage storage)]

    ;; Write magic numbers
    (.writeUTF ostream "PENPOT_CUSTOM_FILE")

    ;; Write data blob
    (.writeLong ostream (alength data))
    (.write ostream data 0 (alength data))

    (let [used  (#'tfm/collect-used-media data')
          items (retrieve-file-media pool used)]
      (.writeLong ostream (count items))
      (doseq [{:keys [media-id thumbnail-id]} items]
        (when-let [obj (some->> media-id (sto/get-object storage))]
          (doto ostream
            (.writeLong (uuid/get-word-high media-id))
            (.writeLong (uuid/get-word-low media-id)))
          (.writeLong ostream (:size obj))
          (let [buff (byte-array buffer-size)]
            (with-open [istream (sto/get-object-data storage obj)]
              (IOUtils/copyLarge istream ostream 0 (:size obj) buff))))))))

(defn read-import!
  [{:keys [pool storage profile-id]} ^DataInputStream istream]
  (let [mnum       (.readUTF istream)
        file-id    (uuid/next)
        project-id (some-> (profile/retrieve-additional-data pool profile-id) :default-project-id)]

    (when-not project-id
      (ex/raise :type :validation
                :code :unable-to-lookup-project))

    (when (not= mnum "PENPOT_CUSTOM_FILE")
      (ex/raise :type :validation
                :code :invalid-import-file))

    (db/with-atomic [conn pool]
      (let [blen    (.readLong istream)
            bdata   (byte-array blen)
            fname   (str "imported-custom-file-" (dt/now))
            storage (media/configure-assets-storage storage conn)]

        (.readFully istream bdata 0 blen)
        (m.files/create-file conn {:id file-id
                                   :name fname
                                   :project-id project-id
                                   :profile-id profile-id
                                   :data bdata})

        (let [nmedia (.readLong istream)]
          (dotimes [i nmedia]
            (prn "importing media" i)
            (let [huid    (.readLong istream)
                  luid    (.readLong istream)
                  size    (.readLong istream)
                  id      (uuid/custom luid huid)
                  content (sto/content (doto (BoundedInputStream. istream size)
                                         (.setPropagateClose false))
                                       size)
                  sobj    (sto/put-object storage {:content content})]
              (db/insert! conn :file-media-object
                          {:id (uuid/next)
                           :file-id file-id
                           :is-local false
                           :name "test"
                           :media-id (:id sobj)
                           :thumbnail-id nil
                           :width 100
                           :height 100
                           :mtype "image/png"}))))))))

(defn export-handler
  [cfg request]
  (let [file-id (some-> (get-in request [:params :file-id]) uuid/uuid)]
    (when-not file-id
      (ex/raise :type :validation
                :code :missing-arguments))

    {:status 200
     :headers {"content-type" "application/octet-stream"
               "content-disposition" "attachment"}
     :body (reify rp/StreamableResponseBody
             (write-body-to-stream [_ _ output-stream]
               (time
                (try
                  (with-open [ostream (DataOutputStream. output-stream)]
                    (write-export! (assoc cfg :file-id file-id) ostream))
                  (catch org.eclipse.jetty.io.EofException _cause
                    ;; Do nothing, EOF means client closes connection abruptly
                    nil)
                  (catch Throwable cause
                    (l/warn :hint "unexpected exception on writing export"
                            :cause cause))))))}))


(defn import-handler
  [cfg {:keys [params profile-id] :as request}]
  (when-not (contains? params :file)
    (ex/raise :type :validation
              :code :missing-upload-file
              :hint "missing upload file"))
  (with-open [istream (io/input-stream (-> params :file :tempfile))]
    (with-open [istream (DataInputStream. istream)]
      (let [cfg (assoc cfg :profile-id profile-id)]
        (read-import! cfg istream)
        {:status 200
         :headers {"content-type" "text/plain"}
         :body "OK"}))))
