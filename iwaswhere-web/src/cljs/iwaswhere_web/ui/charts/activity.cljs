(ns iwaswhere-web.ui.charts.activity
  (:require [reagent.core :as rc]
            [iwaswhere-web.ui.charts.common :as cc]))

(defn weight-line
  "Draws line chart, for example for weight or LBM."
  [indexed local y-start y-end cls val-k ctrl-x ctrl-y]
  (let [chart-h (- y-end y-start)
        vals (filter second (map (fn [[k v]] [k (-> v :weight val-k)])
                                 indexed))
        max-val (or (apply max (map second vals)) 10)
        min-val (or (apply min (map second vals)) 1)
        y-scale (/ chart-h (- max-val min-val))
        mapper (fn [[idx v]]
                 (let [x (+ 5 (* 10 idx))
                       y (- (+ chart-h y-start) (* y-scale (- v min-val)))]
                   (str x "," y)))
        points (cc/line-points vals mapper)
        toggle-show #(swap! local update-in [val-k] not)]
    [:g {:class cls}
     [:circle {:cx ctrl-x :cy ctrl-y :r 8 :on-click toggle-show}]
     (when (val-k @local)
       [:g
        [:polyline {:points points}]
        (for [[idx v] (filter #(:weight (second %)) indexed)]
          (let [w (val-k (:weight v))
                mouse-enter-fn (cc/mouse-enter-fn local v)
                mouse-leave-fn (cc/mouse-leave-fn local v)
                cy (- (+ chart-h y-start) (* y-scale (- w min-val)))]
            ^{:key (str "weight" idx)}
            [:circle {:cx             (+ (* 10 idx) 5)
                      :cy             cy
                      :r              4
                      :on-mouse-enter mouse-enter-fn
                      :on-mouse-leave mouse-leave-fn}]))])]))

(defn activity-bars
  "Renders bars for each day's activity."
  [indexed local y-start y-end]
  [:g
   (for [[idx v] indexed]
     (let [chart-h (- y-end y-start)
           max-val (apply max (map (fn [[_idx v]] (:total-exercise v)) indexed))
           y-scale (/ chart-h (or max-val 1))
           h (* y-scale (:total-exercise v))
           mouse-enter-fn (cc/mouse-enter-fn local v)
           mouse-leave-fn (cc/mouse-leave-fn local v)]
       (when (pos? max-val)
         ^{:key (str "actbar" idx)}
         [:rect {:x              (* 10 idx)
                 :y              (- y-end h)
                 :width          9
                 :height         h
                 :class          (cc/weekend-class "activity" v)
                 :on-mouse-enter mouse-enter-fn
                 :on-mouse-leave mouse-leave-fn}])))])

(defn activity-weight-chart
  "Draws chart for daily activities vs weight. Weight is a line chart with
   circles for each value, activites are represented as bars. On mouse-over
   on top of bars or circles, a small info div next to the hovered item is
   shown."
  [stats chart-h]
  (let [local (rc/atom {:value true
                        :lbm   false})]
    (fn [stats chart-h]
      (let [indexed (map-indexed (fn [idx [k v]] [idx v]) stats)]
        [:div
         [:svg
          {:viewBox (str "0 0 600 " chart-h)}
          ;[cc/chart-title "keep eating slowly"]
          [cc/chart-title "activity/weight"]
          [activity-bars indexed local 160 250]
          [weight-line indexed local 50 150 "lbm" :lbm 42 20]
          [weight-line indexed local 50 150 "weight" :value 20 20]]
         (when (:mouse-over @local)
           [:div.mouse-over-info (cc/info-div-pos @local)
            [:span (:date-string (:mouse-over @local))] [:br]
            [:span "Total min: " (:total-exercise (:mouse-over @local))] [:br]
            [:span "Weight: " (:value (:weight (:mouse-over @local)))] [:br]
            [:span "LBM: " (:lbm (:weight (:mouse-over @local)))]])]))))
