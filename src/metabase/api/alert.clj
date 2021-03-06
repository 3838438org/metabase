(ns metabase.api.alert
  "/api/alert endpoints"
  (:require [clojure.data :as data]
            [compojure.core :refer [DELETE GET POST PUT]]
            [medley.core :as m]
            [metabase
             [email :as email]
             [util :as u]]
            [metabase.api
             [common :as api]
             [pulse :as pulse-api]]
            [metabase.email.messages :as messages]
            [metabase.models
             [interface :as mi]
             [pulse :as pulse :refer [Pulse]]]
            [metabase.util.schema :as su]
            [schema.core :as s]
            [toucan.db :as db]))

(defn- add-read-only-flag [alerts]
  (for [alert alerts
        :let  [can-read?  (mi/can-read? alert)
               can-write? (mi/can-write? alert)]
        :when (or can-read?
                  can-write?)]
    (assoc alert :read_only (not can-write?))))

(api/defendpoint GET "/"
  "Fetch all alerts"
  []
  (add-read-only-flag (pulse/retrieve-alerts)))

(api/defendpoint GET "/question/:id"
  "Fetch all questions for the given question (`Card`) id"
  [id]
  (add-read-only-flag (if api/*is-superuser?*
                        (pulse/retrieve-alerts-for-card id)
                        (pulse/retrieve-user-alerts-for-card id api/*current-user-id*))))

(def ^:private AlertConditions
  (s/enum "rows" "goal"))

(defn- only-alert-keys [request]
  (select-keys request [:alert_condition :alert_first_only :alert_above_goal]))

(api/defendpoint POST "/"
  "Create a new alert (`Pulse`)"
  [:as {{:keys [alert_condition card channels alert_first_only alert_above_goal] :as req} :body}]
  {alert_condition   AlertConditions
   alert_first_only  s/Bool
   alert_above_goal  (s/maybe s/Bool)
   card              su/Map
   channels          (su/non-empty [su/Map])}
  (pulse-api/check-card-read-permissions [card])
  (let [new-alert (api/check-500
                   (-> req
                       only-alert-keys
                       (pulse/create-alert! api/*current-user-id* (u/get-id card) channels)))]
    (when (email/email-configured?)
      (messages/send-new-alert-email! new-alert))

    new-alert))

(defn- recipient-ids [{:keys [channels] :as alert}]
  (reduce (fn [acc {:keys [channel_type recipients]}]
            (if (= :email channel_type)
              (into acc (map :id recipients))
              acc))
          #{} channels))

(defn- check-alert-update-permissions
  "Admin users can update all alerts. Non-admin users can update alerts that they created as long as they are still a
  recipient of that alert"
  [alert]
  (when-not api/*is-superuser?*
    (api/write-check alert)
    (api/check-403 (and (= api/*current-user-id* (:creator_id alert))
                        (contains? (recipient-ids alert) api/*current-user-id*)))))

(defn- email-channel [alert]
  (m/find-first #(= :email (:channel_type %)) (:channels alert)))

(defn- slack-channel [alert]
  (m/find-first #(= :slack (:channel_type %)) (:channels alert)))

(defn- key-by [key-fn coll]
  (zipmap (map key-fn coll) coll))

(defn- notify-recipient-changes!
  "This function compares `OLD-ALERT` and `UPDATED-ALERT` to determine if there have been any recipient related
  changes. Recipients that have been added or removed will be notified."
  [old-alert updated-alert]
  (let [{old-recipients :recipients} (email-channel old-alert)
        {new-recipients :recipients} (email-channel updated-alert)
        old-ids->users (key-by :id old-recipients)
        new-ids->users (key-by :id new-recipients)
        [removed-ids added-ids _] (data/diff (set (keys old-ids->users))
                                             (set (keys new-ids->users)))]
    (doseq [old-id removed-ids
            :let [removed-user (get old-ids->users old-id)]]
      (messages/send-admin-unsubscribed-alert-email! old-alert removed-user @api/*current-user*))

    (doseq [new-id added-ids
            :let [added-user (get new-ids->users new-id)]]
      (messages/send-you-were-added-alert-email! updated-alert added-user @api/*current-user*))))

(api/defendpoint PUT "/:id"
  "Update a `Alert` with ID."
  [id :as {{:keys [alert_condition card channels alert_first_only alert_above_goal card channels] :as req} :body}]
  {alert_condition  AlertConditions
   alert_first_only s/Bool
   alert_above_goal (s/maybe s/Bool)
   card             su/Map
   channels         (su/non-empty [su/Map])}
  (let [old-alert     (pulse/retrieve-alert id)
        _             (check-alert-update-permissions old-alert)
        updated-alert (-> req
                          only-alert-keys
                          (assoc :id id :card (u/get-id card) :channels channels)
                          pulse/update-alert!)]

    ;; Only admins can update recipients
    (when (and api/*is-superuser?* (email/email-configured?))
      (notify-recipient-changes! old-alert updated-alert))

    updated-alert))

(defn- should-unsubscribe-delete?
  "An alert should be deleted instead of unsubscribing if
     - the unsubscriber is the creator
     - they are the only recipient
     - there is no slack channel"
  [alert unsubscribing-user-id]
  (let [{:keys [recipients]} (email-channel alert)]
    (and (= unsubscribing-user-id (:creator_id alert))
         (= 1 (count recipients))
         (= unsubscribing-user-id (:id (first recipients)))
         (nil? (slack-channel alert)))))

(api/defendpoint PUT "/:id/unsubscribe"
  "Unsubscribes a user from the given alert"
  [id]
  ;; Admins are not allowed to unsubscribe from alerts, they should edit the alert
  (api/check (not api/*is-superuser?*)
    [400 "Admin user are not allowed to unsubscribe from alerts"])
  (let [alert (pulse/retrieve-alert id)]
    (api/read-check alert)

    (if (should-unsubscribe-delete? alert api/*current-user-id*)
      ;; No need to unsubscribe if we're just going to delete the Pulse
      (db/delete! Pulse :id id)
      ;; There are other receipieints, remove current user only
      (pulse/unsubscribe-from-alert id api/*current-user-id*))

    (when (email/email-configured?)
      (messages/send-you-unsubscribed-alert-email! alert @api/*current-user*))

    api/generic-204-no-content))

(defn- collect-alert-recipients [alert]
  (set (:recipients (email-channel alert))))

(api/defendpoint DELETE "/:id"
  "Remove an alert"
  [id]
  (api/let-404 [alert (pulse/retrieve-alert id)]
    (api/check-superuser)

    ;; When an alert is deleted, we notify the creator that their alert is being deleted and any recipieint that they
    ;; are no longer going to be receiving that alert
    (let [creator (:creator alert)
          ;; The creator might also be a recipient, no need to notify them twice
          recipients (remove #(= (:id creator) (:id %)) (collect-alert-recipients alert))]

      (db/delete! Pulse :id id)

      (when (email/email-configured?)
        (doseq [recipient recipients]
          (messages/send-admin-unsubscribed-alert-email! alert recipient @api/*current-user*))
        (messages/send-admin-deleted-your-alert! alert creator @api/*current-user*)))

    api/generic-204-no-content))

(api/define-routes)
