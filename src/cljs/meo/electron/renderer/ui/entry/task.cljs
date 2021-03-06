(ns meo.electron.renderer.ui.entry.task
  (:require [matthiasn.systems-toolbox.component :as st]
            [moment]
            [re-frame.core :refer [subscribe]]
            [meo.electron.renderer.helpers :as h]
            [clojure.set :as set]))

(defn task-details [entry local-cfg put-fn _edit-mode?]
  (let [planning-mode (subscribe [:planning-mode])
        prio-select (fn [entry]
                      (fn [ev]
                        (let [sel (keyword (h/target-val ev))
                              updated (assoc-in entry [:task :priority] sel)]
                          (put-fn [:entry/update-local updated]))))
        close-tab (fn []
                    (when (= (str (:timestamp entry)) (:search-text local-cfg))
                      (put-fn [:search/remove local-cfg])))
        done (fn [entry]
               (fn [_ev]
                 (let [completion-ts (.format (moment))
                       entry (-> entry
                                 (assoc-in [:task :completion-ts] completion-ts)
                                 (update-in [:task :done] not))
                       set-fn (if (get-in entry [:task :done])
                                set/union
                                set/difference)
                       entry (-> entry
                                 (update-in [:perm-tags] set-fn #{"#done"})
                                 (update-in [:tags] set-fn #{"#done"}))]
                   (put-fn [:entry/update entry])
                   (close-tab))))
        hold (fn [entry]
               (fn [_ev]
                 (let [updated (update-in entry [:task :on-hold] not)]
                   (put-fn [:entry/update updated]))))]
    (fn [entry _local-cfg put-fn edit-mode?]
      (when (and (or (contains? (:perm-tags entry) "#task")
                     (contains? (:tags entry) "#task"))
                 @planning-mode)
        (when (and edit-mode? (not (:task entry)))
          (let [d (* 24 60 60 1000)
                now (st/now)
                updated (assoc-in entry [:task] {:due (+ now d d)})]
            (put-fn [:entry/update-local updated])))
        (let [allocation (get-in entry [:task :estimate-m] 0)]
          [:form.task-details
           [:fieldset
            [:div
             [:span " Priority: "]
             [:select {:value     (get-in entry [:task :priority] "")
                       :on-change (prio-select entry)}
              [:option ""]
              [:option {:value :A} "A"]
              [:option {:value :B} "B"]
              [:option {:value :C} "C"]
              [:option {:value :D} "D"]
              [:option {:value :E} "E"]]
             [:span
              [:label "Done? "]
              [:input {:type      :checkbox
                       :checked   (get-in entry [:task :done])
                       :on-change (done entry)}]
              [:label "On hold? "]
              [:input {:type      :checkbox
                       :checked   (get-in entry [:task :on-hold])
                       :on-change (hold entry)}]]]
            [:span
             [:label "Reward points: "]
             [:input {:type      :number
                      :on-change (h/update-numeric entry [:task :points] put-fn)
                      :value     (get-in entry [:task :points] 0)}]
             [:label "Allocation: "]
             [:input {:on-change (h/update-time entry [:task :estimate-m] put-fn)
                      :value     (when allocation
                                   (h/m-to-hh-mm allocation))
                      :type      :time}]]]])))))
