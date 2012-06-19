(ns leiningen.deploy-uberjar
  "Build and deploy standalone jar to remote repository.

This code blatantly ripped from lein's standard deploy task."
  (:require [leiningen.core.main :as main]
            [leiningen.uberjar :as uberjar]
            [leiningen.pom :as pom]
            [leiningen.core.classpath :as classpath]
            [cemerick.pomegranate.aether :as aether]
            [clojure.java.io :as io]))

(defn- abort-message [message]
  (cond (re-find #"Return code is 405" message)
        (str message "\n" "Ensure you are deploying over SSL.")
        (re-find #"Return code is 401" message)
        (str message "\n" "See `lein help deploy` for an explanation of how to"
             " specify credentials.")
        :else message))

(defn add-auth-interactively [[id settings]]
  (if (or (and (:username settings) (some settings [:password :passphrase
                                                    :private-key-file]))
          (.startsWith (:url settings) "file://"))
    [id settings]
    (do
      (println "No credentials found for" id)
      (println "See `lein help deploying` for how to configure credentials.")
      (print "Username: ") (flush)
      (let [username (read-line)
            password (.readPassword (System/console) "%s"
                                    (into-array ["Password: "]))]
        [id (assoc settings :username username :password password)]))))

(defn deploy-uberjar
  "Build uberjar and deploy to remote repository.

The target repository will be looked up in :repositories in project.clj:

  :repositories {\"snapshots\" \"https://internal.repo/snapshots\"
                 \"releases\" \"https://internal.repo/releases\"
                 \"alternate\" \"https://other.server/repo\"}

If you don't provide a repository name to deploy to, either \"snapshots\" or
\"releases\" will be used depending on your project's current version. See
`lein help deploying` under \"Authentication\" for instructions on how to
configure your credentials so you are not prompted on each deploy."
  ([project repository-name]
     (doseq [key [:description :license :url]]
       (when (or (nil? (project key)) (re-find #"FIXME" (str (project key))))
         (main/info "WARNING: please set" key "in project.clj.")))
     (let [jarfile (uberjar/uberjar project)
           pomfile (pom/pom project)
           ;; can't use merge here due to bug in ordered maps:
           ;; https://github.com/flatland/ordered/issues/4
           repo-opts (or (get (:deploy-repositories project) repository-name)
                         (get (:repositories project) repository-name))
           repo (cond (not repo-opts) ["inline" {:url repository-name}]
                      (string? repo-opts) [repository-name {:url repo-opts}]
                      :else [repository-name repo-opts])
           repo (classpath/add-repo-auth repo)
           repo (add-auth-interactively repo)]
       (main/debug "Deploying to" repo)
       (try (aether/deploy :coordinates [(symbol (:group project)
                                                 (:name project))
                                         (:version project)]
                           :jar-file (io/file jarfile)
                           :pom-file (io/file pomfile)
                           :transfer-listener :stdout
                           :repository [repo])
            (catch org.sonatype.aether.deployment.DeploymentException e
              (main/abort (abort-message (.getMessage e)))))))
  ([project]
     (deploy-uberjar project (if (pom/snapshot? project)
                               "snapshots"
                               "releases"))))
