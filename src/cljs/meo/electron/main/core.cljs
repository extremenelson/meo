(ns meo.electron.main.core
  (:require [meo.electron.main.log]
            [meo.common.specs]
            [taoensso.timbre :refer-macros [info]]
            [matthiasn.systems-toolbox-electron.ipc-main :as ipc]
            [matthiasn.systems-toolbox-electron.window-manager :as wm]
            [meo.electron.main.menu :as menu]
            [meo.electron.main.update :as upd]
            [meo.electron.main.blink :as bl]
            [meo.electron.main.encryption :as enc]
            [meo.electron.main.geocoder :as geocoder]
            [meo.electron.main.startup :as st]
            [electron :refer [app]]
            [matthiasn.systems-toolbox.scheduler :as sched]
            [matthiasn.systems-toolbox.switchboard :as sb]
            [cljs.nodejs :as nodejs :refer [process]]
            [meo.electron.main.runtime :as rt]
            [cljs.pprint :as pp]))

(when-not (aget js/goog "global" "setTimeout")
  (info "goog.global.setTimeout not defined - let's change that")
  (aset js/goog "global" "setTimeout" js/setTimeout))

(aset process "env" "GOOGLE_API_KEY" "AIzaSyD78NTnhgt--LCGBdIGPEg8GtBYzQl0gKU")

(defonce switchboard (sb/component :electron/switchboard))

(def OBSERVER true)

(defn make-observable [components]
  (if OBSERVER
    (let [mapper #(assoc-in % [:opts :msgs-on-firehose] true)]
      (set (mapv mapper components)))
    components))

(def wm-relay #{:exec/js
                :cmd/toggle-key
                :update/status
                :screenshot/take
                :entry/update
                :entry/sync
                :geonames/res
                :spellcheck/lang
                :spellcheck/off
                :import/screenshot
                :import/photos
                :import/listen
                :firehose/cmp-put
                :firehose/cmp-recv})

(def app-path (:app-path rt/runtime-info))

(defn start []
  (info "Starting CORE:" (with-out-str (pp/pprint rt/runtime-info)))
  (let [components #{(wm/cmp-map :electron/window-manager wm-relay app-path)
                     (st/cmp-map :electron/startup)
                     (ipc/cmp-map :electron/ipc-cmp)
                     (bl/cmp-map :electron/blink)
                     (enc/cmp-map :electron/encryption)
                     (upd/cmp-map :electron/updater)
                     (sched/cmp-map :electron/scheduler)
                     (menu/cmp-map :electron/menu-cmp)
                     (geocoder/cmp-map :electron/geocoder #{:geonames/lookup})}
        components (make-observable components)]
    (sb/send-mult-cmd
      switchboard
      [[:cmd/init-comp components]

       [:cmd/route {:from :electron/menu-cmp
                    :to   #{:electron/window-manager
                            :electron/startup
                            :electron/scheduler
                            :electron/geocoder
                            :electron/updater}}]

       [:cmd/route {:from :electron/scheduler
                    :to   #{:electron/updater
                            :electron/window-manager
                            :electron/geocoder
                            :electron/encryption
                            :electron/blink
                            :electron/startup}}]

       [:cmd/route {:from :electron/ipc-cmp
                    :to   #{:electron/startup
                            :electron/updater
                            :electron/geocoder
                            :electron/blink
                            :electron/encryption
                            :electron/scheduler
                            :electron/window-manager}}]

       [:cmd/route {:from :electron/blink
                    :to   :electron/scheduler}]

       [:cmd/route {:from :electron/window-manager
                    :to   :electron/startup}]

       [:cmd/route {:from :electron/geocoder
                    :to   :electron/window-manager}]

       [:cmd/route {:from :electron/encryption
                    :to   :electron/window-manager}]

       [:cmd/route {:from :electron/updater
                    :to   #{:electron/scheduler
                            :electron/window-manager
                            :electron/startup}}]

       [:cmd/route {:from :electron/startup
                    :to   #{:electron/scheduler
                            :electron/window-manager}}]

       (when OBSERVER
         [:cmd/attach-to-firehose :electron/window-manager])

       [:cmd/send {:to  :electron/startup
                   :msg [:jvm/loaded?]}]

       #_
       [:cmd/send {:to  :electron/scheduler
                   :msg [:cmd/schedule-new {:message [:geocoder/start]
                                            :timeout 2000}]}]
#_
       [:cmd/send {:to  :electron/scheduler
                   :msg [:cmd/schedule-new {:timeout (* 10 1000)
                                            :message [:sync/scan-inbox]
                                            :repeat  true
                                            :initial true}]}]
#_
       [:cmd/send {:to  :electron/scheduler
                   :msg [:cmd/schedule-new {:timeout (* 10 1000)
                                            :message [:sync/scan-images]
                                            :repeat  true
                                            :initial true}]}]

       [:cmd/send {:to  :electron/scheduler
                   :msg [:cmd/schedule-new {:timeout (* 24 60 60 1000)
                                            :message [:update/auto-check]
                                            :repeat  true
                                            :initial true}]}]])))

(.on app "ready" start)
