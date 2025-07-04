(ns metabase-enterprise.advanced-permissions.common
  (:require
   [metabase.api.common :as api]
   [metabase.permissions.core :as perms]
   [metabase.premium-features.core :as premium-features :refer [defenterprise]]
   [metabase.util :as u]
   [metabase.warehouses.models.database :as database]
   [toucan2.core :as t2]))

(defenterprise current-user-can-write-field?
  "Enterprise version. Returns a boolean whether the current user can write the given field."
  :feature :advanced-permissions
  [instance]
  (let [db-id (or (get-in instance [:table :db_id])
                  (database/table-id->database-id (:table_id instance)))]
    (perms/user-has-permission-for-table?
     api/*current-user-id*
     :perms/manage-table-metadata
     :yes
     db-id
     (:table_id instance))))

(defenterprise current-user-can-manage-schema-metadata?
  "Enterprise version. Returns a boolean whether the current user has permission to edit table metadata for any tables
  in the schema"
  :feature :advanced-permissions
  [db-id schema-name]
  (perms/user-has-permission-for-schema?
   api/*current-user-id*
   :perms/manage-table-metadata
   :yes
   db-id
   schema-name))

(defenterprise current-user-can-write-db?
  "Enterprise version. Returns a boolean whether the current user can write the given db"
  :feature :advanced-permissions
  [db-id]
  (perms/user-has-permission-for-database?
   api/*current-user-id*
   :perms/manage-database
   :yes
   db-id))

(defenterprise current-user-can-write-table?
  "Enterprise version."
  :feature :advanced-permissions
  [table]
  (perms/user-has-permission-for-table?
   api/*current-user-id*
   :perms/manage-table-metadata
   :yes
   (:db_id table)
   (:id table)))

(defn with-advanced-permissions
  "Adds to `user` a set of boolean flag indiciate whether or not current user has access to an advanced permissions.
  This function is meant to be used for GET /api/user/current "
  [user]
  (let [permissions-set @api/*current-user-permissions-set*]
    (assoc user :permissions
           {:can_access_setting      (perms/set-has-application-permission-of-type? permissions-set :setting)
            :can_access_subscription (perms/set-has-application-permission-of-type? permissions-set :subscription)
            :can_access_monitoring   (perms/set-has-application-permission-of-type? permissions-set :monitoring)
            :can_access_data_model   (perms/user-has-any-perms-of-type? api/*current-user-id* :perms/manage-table-metadata)
            :can_access_db_details   (perms/user-has-any-perms-of-type? api/*current-user-id* :perms/manage-database)
            :is_group_manager        api/*is-group-manager?*})))

(defenterprise current-user-has-application-permissions?
  "Check if `*current-user*` has permissions for a application permissions of type `perm-type`."
  :feature :advanced-permissions
  [perm-type]
  (or api/*is-superuser?*
      (perms/set-has-application-permission-of-type? @api/*current-user-permissions-set* perm-type)))

(defn current-user-is-manager-of-group?
  "Return true if current-user is a manager of `group-or-id`."
  [group-or-id]
  (t2/select-one-fn :is_group_manager :model/PermissionsGroupMembership
                    :user_id api/*current-user-id* :group_id (u/the-id group-or-id)))

(defn filter-tables-by-data-model-perms
  "Given a list of tables, removes the ones for which `*current-user*` does not have data model editing permissions."
  [tables]
  (cond
    api/*is-superuser?*
    tables

    ;; If advanced-permissions is not enabled, no non-admins have any data-model editing perms, so return an empty list
    (not (premium-features/enable-advanced-permissions?))
    (empty tables)

    :else
    (filter
     (fn [{table-id :id db-id :db_id}]
       (perms/user-has-permission-for-table?
        api/*current-user-id*
        :perms/manage-table-metadata
        :yes
        db-id
        table-id))
     tables)))

(defn filter-schema-by-data-model-perms
  "Given a list of schema, remove the ones for which `*current-user*` does not have data model editing permissions."
  [schema]
  (cond
    api/*is-superuser?*
    schema

    ;; If advanced-permissions is not enabled, no non-admins have any data-model editing perms, so return an empty list
    (not (premium-features/enable-advanced-permissions?))
    (empty schema)

    :else
    (filter
     (fn [{db-id :db_id schema :schema}]
       (perms/user-has-permission-for-schema?
        api/*current-user-id*
        :perms/manage-table-metadata
        :yes
        db-id
        schema))
     schema)))

(defn filter-databases-by-data-model-perms
  "Given a list of databases, removes the ones for which `*current-user*` has no data model editing permissions.
  If databases are already hydrated with their tables, also removes tables for which `*current-user*` has no data
  model editing perms."
  [dbs]
  (cond
    api/*is-superuser?*
    dbs

    ;; If advanced-permissions is not enabled, no non-admins have any data-model editing perms, so return an empty list
    (not (premium-features/enable-advanced-permissions?))
    (empty dbs)

    :else
    (reduce
     (fn [result {db-id :id tables :tables :as db}]
       (if (= (perms/most-permissive-database-permission-for-user api/*current-user-id* :perms/manage-table-metadata db-id)
              :yes)
         (if tables
           (conj result (update db :tables filter-tables-by-data-model-perms))
           (conj result db))
         result))
     []
     dbs)))

(defenterprise new-database-view-data-permission-level
  "Returns the default view-data permission level for a new database for a given group. This is `blocked` if the
  group has block permissions for any existing database, or if any connection impersonation policies or sandboxes
  exist. Otherwise, it is `unrestricted`."
  :feature :advanced-permissions
  [group-id]
  (if (or
       (t2/exists? :model/DataPermissions
                   :perm_type :perms/view-data
                   :perm_value :blocked
                   :group_id group-id)
       (t2/exists? :model/ConnectionImpersonation
                   :group_id group-id)
       (and
        (premium-features/enable-sandboxes?)
        (t2/exists? :model/GroupTableAccessPolicy
                    :group_id group-id)))
    :blocked
    :unrestricted))

(defenterprise new-table-view-data-permission-level
  "Returns the view-data permission level to set for a new table in a given group and database. This is `blocked`
  if the group has `blocked` for the database or any table in the DB, if any connection impersonation policies or
  sandboxes exist for the database and group. otherwise it is `unrestricted`."
  :feature :advanced-permissions
  [db-id group-id]
  ;; We don't check for connection impersonations here, because impersonations are set at the DB-level, so a new table
  ;; should get `:unrestricted` permissions and then inherit the DB-level impersonation policy.
  (if (or
       (t2/exists? :model/DataPermissions
                   :db_id db-id
                   :perm_type :perms/view-data
                   :perm_value :blocked
                   :group_id group-id)
       (and
        (premium-features/enable-sandboxes?)
        (t2/exists?
         :model/GroupTableAccessPolicy
         {:select [:s.id]
          :from [[(t2/table-name :model/GroupTableAccessPolicy) :s]]
          :join [[(t2/table-name :model/Table) :t] [:= :t.id :s.table_id]]
          :where [:and
                  [:= :s.group_id group-id]
                  [:= :t.db_id db-id]]})))
    :blocked
    :unrestricted))

(defenterprise new-group-view-data-permission-level
  "Returns the default view-data permission level for a new group for a given database. This is `blocked` if All Users
  has block permissions for the database, or if any connection impersonation policies or sandboxes exist. Otherwise, it
  is `unrestricted`."
  :feature :advanced-permissions
  [db-id]
  (let [all-users-group-id (u/the-id (perms/all-users-group))]
    (if (or
         (t2/exists? :model/DataPermissions
                     :perm_type :perms/view-data
                     :perm_value :blocked
                     :db_id db-id
                     :group_id all-users-group-id)
         (t2/exists? :model/ConnectionImpersonation
                     :group_id all-users-group-id
                     :db_id db-id)
         (and
          (premium-features/enable-sandboxes?)
          (t2/exists? :model/GroupTableAccessPolicy
                      :group_id all-users-group-id
                      {:from [[:sandboxes :s]]
                       :join [[:metabase_table :t] [:= :s.table_id :t.id]]
                       :where [:= :t.db_id db-id]})))
      :blocked
      :unrestricted)))
