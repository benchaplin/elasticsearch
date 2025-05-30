---
mapped_pages:
  - https://www.elastic.co/guide/en/elasticsearch/reference/current/audit-event-types.html
applies_to:
  deployment:
    ess: all
    ece: all
    eck: all
---

# Elasticsearch audit events [elasticsearch-audit-events]

:::{note}
This section provides detailed **reference information** for Elasticsearch audit events.

Refer to [Security event audit logging](docs-content://deploy-manage/security/logging-configuration/security-event-audit-logging.md) in the **Deploy and manage** section for overview, getting started and conceptual information about audit logging.
:::

When you are [auditing security events](docs-content://deploy-manage/security/logging-configuration/enabling-audit-logs.md), a single client request might generate multiple audit events, across multiple cluster nodes. The common `request.id` attribute can be used to correlate the associated events.

This document provides a reference for all types of audit events and their associated [attributes](#audit-event-attributes) in {{es}}. Use [audit event settings](./configuration-reference/auding-settings.md) options to control what gets logged.

For more information and options about tuning audit logs, refer to [Configuring audit logs](docs-content://deploy-manage/security/logging-configuration/configuring-audit-logs.md).

::::{note}
Certain audit events require the `security_config_change` event type to log the related event action. The event descriptions in this document indicate whether this requirement is applicable.
::::

## Audit event types [audit-event-types]

$$$event-access-denied$$$

`access_denied`
:   Logged when an authenticated user attempts to execute an action they do not have the necessary [privilege](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/elasticsearch-privileges.md) to perform.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T22:30:06,949+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"transport", "event.action":
    "access_denied", "authentication.type":"REALM", "user.name":"user1",
    "user.realm":"default_native", "user.roles":["test_role"], "origin.type":
    "rest", "origin.address":"[::1]:52434", "request.id":"yKOgWn2CRQCKYgZRz3phJw",
    "action":"indices:admin/auto_create", "request.name":"CreateIndexRequest",
    "indices":["<index-{now/d+1d}>"]}
    ```
    ::::


$$$event-access-granted$$$

`access_granted`
:   Logged when an authenticated user attempts to execute an action they have the necessary privilege to perform. These events will be logged only for non-system users.

    If you want to include `access_granted` events for all users (including internal users such as `_xpack`), add [`system_access_granted`](#event-system-granted) to the list of event types in addition to `access_granted`. The `system_access_granted` privilege is not included by default to avoid cluttering the logs.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T22:30:06,947+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"transport", "event.action":
    "access_granted", "authentication.type":"REALM", "user.name":"user1", "user
    realm":"default_native", "user.roles":["test_role"], "origin.type":"rest",
    "origin.address":"[::1]:52434", "request.id":"yKOgWn2CRQCKYgZRz3phJw",
    "action":"indices:data/write/bulk", "request.name":"BulkRequest"}
    ```

    ::::


$$$event-anonymous-access-denied$$$

`anonymous_access_denied`
:   Logged when a request is denied due to missing authentication credentials.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T21:56:43,608+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"rest", "event.action":
    "anonymous_access_denied", "origin.type":"rest", "origin.address":
    "[::1]:50543", "url.path":"/twitter/_async_search", "url.query":"pretty",
    "request.method":"POST", "request.id":"TqA9OisyQ8WTl1ivJUV1AA"}
    ```

    ::::


$$$event-authentication-failed$$$

`authentication_failed`
:   Logged when the authentication credentials cannot be matched to a known user.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T22:10:15,510+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"rest", "event.action":
    "authentication_failed", "user.name":"elastic", "origin.type":"rest",
    "origin.address":"[::1]:51504", "url.path":"/_security/user/user1",
    "url.query":"pretty", "request.method":"POST",
    "request.id":"POv8p_qeTl2tb5xoFl0HIg"}
    ```

    ::::


$$$event-authentication-success$$$

`authentication_success`
:   Logged when a user successfully authenticates.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T22:03:35,018+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"rest", "event.action":
    "authentication_success", "authentication.type":"REALM", "user.name":
    "elastic", "user.realm":"reserved", "origin.type":"rest", "origin.address":
    "[::1]:51014", "realm":"reserved", "url.path":"/twitter/_search",
    "url.query":"pretty", "request.method":"POST",
    "request.id":"nHV3UMOoSiu-TaSPWCfxGg"}
    ```

    ::::


$$$event-change-disable-user$$$

`change_disable_user`
:   Logged when the [enable user API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-enable-user) is invoked to disable a native or a built-in user.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T23:17:28,308+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"security_config_change", "event.
    action":"change_disable_user", "request.id":"qvLIgw_eTvyK3cgV-GaLVg",
    "change":{"disable":{"user":{"name":"user1"}}}}
    ```

    ::::


$$$event-change-enable-user$$$

`change_enable_user`
:   Logged when the [enable user API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-enable-user) is invoked to enable a native or a built-in user.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T23:17:34,843+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"security_config_change", "event.
    action":"change_enable_user", "request.id":"BO3QU3qeTb-Ei0G0rUOalQ",
    "change":{"enable":{"user":{"name":"user1"}}}}
    ```

    ::::


$$$event-change-password$$$

`change_password`
:   Logged when the [change password API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-change-password) is invoked to change the password of a native or built-in user.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2019-12-30T22:19:41,345+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"security_config_change", "event.
    action":"change_password", "request.id":"bz5a1Cc3RrebDMitMGGNCw",
    "change":{"password":{"user":{"name":"user1"}}}}
    ```

    ::::


$$$event-create-service-token$$$

`create_service_token`
:   Logged when the [create service account token API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-create-service-token) is invoked to create a new index-based token for a service account.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2021-04-30T23:17:42,952+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"security_config_change", "event.
    action":"create_service_token", "request.id":"az9a1Db5QrebDMacQ8yGKc",
    "create":{"service_token":{"namespace":"elastic","service":"fleet-server","name":"token1"}}}`
    ```

    ::::


$$$event-connection-denied$$$

`connection_denied`
:   Logged when an incoming TCP connection does not pass the [IP filter](docs-content://deploy-manage/security/ip-traffic-filtering.md) for a specific profile.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T21:47:31,526+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"ip_filter", "event.action":
    "connection_denied", "origin.type":"rest", "origin.address":"10.10.0.20:52314",
    "transport.profile":".http", "rule":"deny 10.10.0.0/16"}
    ```

    ::::


$$$event-connection-granted$$$

`connection_granted`
:   Logged when an incoming TCP connection passes the [IP filter](docs-content://deploy-manage/security/ip-traffic-filtering.md) for a specific profile.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T21:47:31,526+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"ip_filter", "event.action":
    "connection_granted", "origin.type":"rest", "origin.address":"[::1]:52314",
    "transport.profile":".http", "rule":"allow ::1,127.0.0.1"}
    ```

    ::::


$$$event-create-apikey$$$

`create_apikey`
:   Logged when the [create API key](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-create-api-key) or the [grant API key](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-grant-api-key) APIs are invoked to create a new API key.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-31T00:33:52,521+0200", "node.id":
    "9clhpgjJRR-iKzOw20xBNQ", "event.type":"security_config_change", "event.action":
    "create_apikey", "request.id":"9FteCmovTzWHVI-9Gpa_vQ", "create":{"apikey":
    {"name":"test-api-key-1","expiration":"10d","role_descriptors":[{"cluster":
    ["monitor","manage_ilm"],"indices":[{"names":["index-a*"],"privileges":
    ["read","maintenance"]},{"names":["in*","alias*"],"privileges":["read"],
    "field_security":{"grant":["field1*","@timestamp"],"except":["field11"]}}],
    "applications":[],"run_as":[]},{"cluster":["all"],"indices":[{"names":
    ["index-b*"],"privileges":["all"]}],"applications":[],"run_as":[]}],
    "metadata":{"application":"my-application","environment":{"level": 1,
    "tags":["dev","staging"]}}}}}
    ```

    ::::


$$$event-change-apikey$$$

`change_apikey`
:   Logged when the [update API key](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-update-api-key) API is invoked to update the attributes of an existing API key.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-31T00:33:52,521+0200", "node.id":
    "9clhpgjJRR-iKzOw20xBNQ", "event.type":"security_config_change", "event.action":
    "change_apikey", "request.id":"9FteCmovTzWHVI-9Gpa_vQ", "change":{"apikey":
    {"id":"zcwN3YEBBmnjw-K-hW5_","role_descriptors":[{"cluster":
    ["monitor","manage_ilm"],"indices":[{"names":["index-a*"],"privileges":
    ["read","maintenance"]},{"names":["in*","alias*"],"privileges":["read"],
    "field_security":{"grant":["field1*","@timestamp"],"except":["field11"]}}],
    "applications":[],"run_as":[]},{"cluster":["all"],"indices":[{"names":
    ["index-b*"],"privileges":["all"]}],"applications":[],"run_as":[]}],
    "metadata":{"application":"my-application","environment":{"level": 1,
    "tags":["dev","staging"]}},"expiration":"10d"}}}
    ```

    ::::


$$$event-change-apikeys$$$

`change_apikeys`
:   Logged when the [bulk update API keys](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-bulk-update-api-keys) API is invoked to update the attributes of multiple existing API keys.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit","timestamp":"2020-12-31T00:33:52,521+0200","node.id":
    "9clhpgjJRR-iKzOw20xBNQ","event.type":"security_config_change",
    "event.action":"change_apikeys","request.id":"9FteCmovTzWHVI-9Gpa_vQ",
    "change":{"apikeys":
    {"ids":["zcwN3YEBBmnjw-K-hW5_","j7c0WYIBqecB5CbVR6Oq"],"role_descriptors":
    [{"cluster":["monitor","manage_ilm"],"indices":[{"names":["index-a*"],"privileges":
    ["read","maintenance"]},{"names":["in*","alias*"],"privileges":["read"],
    "field_security":{"grant":["field1*","@timestamp"],"except":["field11"]}}],
    "applications":[],"run_as":[]},{"cluster":["all"],"indices":[{"names":
    ["index-b*"],"privileges":["all"]}],"applications":[],"run_as":[]}],
    "metadata":{"application":"my-application","environment":{"level":1,
    "tags":["dev","staging"]}},"expiration":"10d"}}}
    ```

    ::::


$$$event-delete-privileges$$$

`delete_privileges`
:   Logged when the [delete application privileges API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-delete-privileges) is invoked to remove one or more application privileges.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-31T00:39:30,246+0200", "node.id":
    "9clhpgjJRR-iKzOw20xBNQ", "event.type":"security_config_change", "event.
    action":"delete_privileges", "request.id":"7wRWVxxqTzCKEspeSP7J8g",
    "delete":{"privileges":{"application":"myapp","privileges":["read"]}}}
    ```

    ::::


$$$event-delete-role$$$

`delete_role`
:   Logged when the [delete role API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-delete-role) is invoked to delete a role.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-31T00:08:11,678+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"security_config_change", "event.action":
    "delete_role", "request.id":"155IKq3zQdWq-12dgKZRnw",
    "delete":{"role":{"name":"my_admin_role"}}}
    ```

    ::::


$$$event-delete-role-mapping$$$

`delete_role_mapping`
:   Logged when the [delete role mapping API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-delete-role-mapping) is invoked to delete a role mapping.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-31T00:12:09,349+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"security_config_change", "event.
    action":"delete_role_mapping", "request.id":"Stim-DuoSTCWom0S_xhf8g",
    "delete":{"role_mapping":{"name":"mapping1"}}}
    ```

    ::::


$$$event-delete-service-token$$$

`delete_service_token`
:   Logged when the [delete service account token API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-delete-service-token) is invoked to delete an index-based token for a service account.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2021-04-30T23:17:42,952+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"security_config_change", "event.
    action":"delete_service_token", "request.id":"az9a1Db5QrebDMacQ8yGKc",
    "delete":{"service_token":{"namespace":"elastic","service":"fleet-server","name":"token1"}}}
    ```

    ::::


$$$event-delete-user$$$

`delete_user`
:   Logged when the [delete user API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-delete-user) is invoked to delete a specific native user.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T22:19:41,345+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"security_config_change",
    "event.action":"delete_user", "request.id":"au5a1Cc3RrebDMitMGGNCw",
    "delete":{"user":{"name":"jacknich"}}}
    ```

    ::::


$$$event-invalidate-apikeys$$$

`invalidate_apikeys`
:   Logged when the [invalidate API key API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-invalidate-api-key) is invoked to invalidate one or more API keys.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-31T00:36:30,247+0200", "node.id":
    "9clhpgjJRR-iKzOw20xBNQ", "event.type":"security_config_change", "event.
    action":"invalidate_apikeys", "request.id":"7lyIQU9QTFqSrTxD0CqnTQ",
    "invalidate":{"apikeys":{"owned_by_authenticated_user":false,
    "user":{"name":"myuser","realm":"native1"}}}}
    ```

    ::::


$$$event-put-privileges$$$

`put_privileges`
:   Logged when the [create or update privileges API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-put-privileges) is invoked to add or update one or more application privileges.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-31T00:39:07,779+0200", "node.id":
    "9clhpgjJRR-iKzOw20xBNQ", "event.type":"security_config_change",
    "event.action":"put_privileges", "request.id":"1X2VVtNgRYO7FmE0nR_BGA",
    "put":{"privileges":[{"application":"myapp","name":"read","actions":
    ["data:read/*","action:login"],"metadata":{"description":"Read access to myapp"}}]}}
    ```

    ::::


$$$event-put-role$$$

`put_role`
:   Logged when the [create or update role API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-put-role) is invoked to create or update a role.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T22:27:01,978+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"security_config_change",
    "event.action":"put_role", "request.id":"tDYQhv5CRMWM4Sc5Zkk2cQ",
    "put":{"role":{"name":"test_role","role_descriptor":{"cluster":["all"],
    "indices":[{"names":["apm*"],"privileges":["all"],"field_security":
    {"grant":["granted"]},"query":"{\"term\": {\"service.name\": \"bar\"}}"},
    {"names":["apm-all*"],"privileges":["all"],"query":"{\"term\":
    {\"service.name\": \"bar2\"}}"}],"applications":[],"run_as":[]}}}}
    ```

    ::::


$$$event-put-role-mapping$$$

`put_role_mapping`
:   Logged when the [create or update role mapping API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-put-role-mapping) is invoked to create or update a role mapping.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-31T00:11:13,932+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"security_config_change", "event.
    action":"put_role_mapping", "request.id":"kg4h1l_kTDegnLC-0A-XxA",
    "put":{"role_mapping":{"name":"mapping1","roles":["user"],"rules":
    {"field":{"username":"*"}},"enabled":true,"metadata":{"version":1}}}}
    ```

    ::::


$$$event-put-user$$$

`put_user`
:   Logged when the [create or update user API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-put-user) is invoked to create or update a native user. Note that user updates can also change the user’s password.

    You must include the `security_config_change` event type to audit the related event action.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T22:10:09,749+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"security_config_change",
    "event.action":"put_user", "request.id":"VIiSvhp4Riim_tpkQCVSQA",
    "put":{"user":{"name":"user1","enabled":false,"roles":["admin","other_role1"],
    "full_name":"Jack Sparrow","email":"jack@blackpearl.com",
    "has_password":true,"metadata":{"cunning":10}}}}
    ```

    ::::


$$$event-realm-auth-failed$$$

`realm_authentication_failed`
:   Logged for every realm that fails to present a valid authentication token.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T22:10:15,510+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"rest", "event.action":
    "realm_authentication_failed", "user.name":"elastic", "origin.type":"rest",
    "origin.address":"[::1]:51504", "realm":"myTestRealm1", "url.path":
    "/_security/user/user1", "url.query":"pretty", "request.method":"POST",
    "request.id":"POv8p_qeTl2tb5xoFl0HIg"}
    ```

    ::::


$$$event-runas-denied$$$

`run_as_denied`
:   Logged when an authenticated user attempts to [run as](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/submitting-requests-on-behalf-of-other-users.md) another user that they do not have the necessary [privileges](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/elasticsearch-privileges.md) to do so.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T22:49:34,859+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"transport", "event.action":
    "run_as_denied", "user.name":"user1", "user.run_as.name":"user1",
    "user.realm":"default_native", "user.run_as.realm":"default_native",
    "user.roles":["test_role"], "origin.type":"rest", "origin.address":
    "[::1]:52662", "request.id":"RcaSt872RG-R_WJBEGfYXA",
    "action":"indices:data/read/search", "request.name":"SearchRequest", "indices":["alias1"]}
    ```

    ::::


$$$event-runas-granted$$$

`run_as_granted`
:   Logged when an authenticated user attempts to [run as](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/submitting-requests-on-behalf-of-other-users.md) another user that they have the necessary privileges to do so.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2020-12-30T22:44:42,068+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type":"transport", "event.action":
    "run_as_granted", "user.name":"elastic", "user.run_as.name":"user1",
    "user.realm":"reserved", "user.run_as.realm":"default_native",
    "user.roles":["superuser"], "origin.type":"rest", "origin.address":
    "[::1]:52623", "request.id":"dGqPTdEQSX2TAPS3cvc1qA", "action":
    "indices:data/read/search", "request.name":"SearchRequest", "indices":["alias1"]}
    ```

    ::::


$$$event-system-granted$$$

`system_access_granted`
:   Logs [`access_granted`](#event-access-granted) events only for [internal users](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/internal-users.md), such as `_xpack`. If you include this setting in addition to `access_granted`, then `access_granted` events are logged for *all* users.

    ::::{note}
    This event type is disabled by default to avoid cluttering the logs.
    ::::


$$$event-tampered-request$$$

`tampered_request`
:   Logged when the {{security-features}} detect that the request has been tampered with. Typically relates to `search/scroll` requests when the scroll ID is believed to have been tampered with.

    ::::{dropdown} Example
    ```js
    {"type":"audit", "timestamp":"2019-11-27T22:00:00,947+0200", "node.id":
    "0RMNyghkQYCc_gVd1G6tZQ", "event.type": "rest", "event.action":
    "tampered_request", "origin.address":"[::1]:50543", "url.path":
    "/twitter/_async_search", "url.query":"pretty", "request.method":"POST",
    "request.id":"TqA9OisyQ8WTl1ivJUV1AA"}
    ```

    ::::

## Audit event attributes [audit-event-attributes]

The audit events are formatted as JSON documents, and each event is printed on a separate line in the audit log. The entries themselves do not contain an end-of-line delimiter. For more details, see [Log entry format](docs-content://deploy-manage/security/logging-configuration/logfile-audit-output.md#audit-log-entry-format).

### Common attributes

The following list shows attributes that are common to all audit event types:

`@timestamp`
:   The time, in ISO9601 format, when the event occurred.

`node.name`
:   The name of the node. This can be changed in the `elasticsearch.yml` config file.

`node.id`
:   The node id. This is automatically generated and is persistent across full cluster restarts.

`host.ip`
:   The bound IP address of the node, with which the node can be communicated with.

`host.name`
:   The unresolved node’s hostname.

`event.type`
:   The internal processing layer that generated the event: `rest`, `transport`, `ip_filter` or `security_config_change`. This is different from `origin.type` because a request originating from the REST API is translated to a number of transport messages, generating audit events with `origin.type: rest` and `event.type: transport`.

`event.action`
:   The type of event that occurred: `anonymous_access_denied`, `authentication_failed`, `authentication_success`, `realm_authentication_failed`, `access_denied`, `access_granted`, `connection_denied`, `connection_granted`, `tampered_request`, `run_as_denied`, or `run_as_granted`.

    In addition, if `event.type` equals [`security_config_change`](#security-config-change), the `event.action` attribute takes one of the following values: `put_user`, `change_password`, `put_role`, `put_role_mapping`, `change_enable_user`, `change_disable_user`, `put_privileges`, `create_apikey`, `delete_user`, `delete_role`, `delete_role_mapping`, `invalidate_apikeys`, `delete_privileges`, `change_apikey`, or `change_apikeys`.


`request.id`
:   A synthetic identifier that can be used to correlate the events associated with a particular REST request.

In addition, all the events of types `rest`, `transport` and `ip_filter` (but not `security_config_change`) have the following extra attributes, which show more details about the requesting client:

`origin.address`
:   The source IP address of the request associated with this event. This could be the address of the remote client, the address of another cluster node, or the local node’s bound address, if the request originated locally. Unless the remote client connects directly to the cluster, the *client address* will actually be the address of the first OSI layer 3 proxy in front of the cluster.

`origin.type`
:   The origin type of the request associated with this event: `rest` (request originated from a REST API request), `transport` (request was received on the transport channel), or `local_node` (the local node issued the request).

`opaque_id`
:   The value of the `X-Opaque-Id` HTTP header (if present) of the request associated with this event. See more: [`X-Opaque-Id` HTTP header - API conventions](/reference/elasticsearch/rest-apis/api-conventions.md#x-opaque-id)

`trace_id`
:   The identifier extracted from the `traceparent` HTTP header (if present) of the request associated with this event. It allows to surface audit logs into the Trace Logs feature of Elastic APM.

`x_forwarded_for`
:   The verbatim value of the `X-Forwarded-For` HTTP request header (if present) of the request associated with the audit event. This header is commonly added by proxies when they forward requests and the value is the address of the proxied client. When a request crosses multiple proxies the header is a comma delimited list with the last value being the address of the second to last proxy server (the address of the last proxy server is designated by the `origin.address` field).

### Audit event attributes of the `rest` event type [_audit_event_attributes_of_the_rest_event_type]

The events with `event.type` equal to `rest` have one of the following `event.action` attribute values: `authentication_success`, `anonymous_access_denied`, `authentication_failed`, `realm_authentication_failed`, `tampered_request` or `run_as_denied`. These events also have the following extra attributes (in addition to the common ones):

`url.path`
:   The path part of the URL (between the port and the query string) of the REST request associated with this event. This is URL encoded.

`url.query`
:   The query part of the URL (after "?", if present) of the REST request associated with this event. This is URL encoded.

`request.method`
:   The HTTP method of the REST request associated with this event. It is one of GET, POST, PUT, DELETE, OPTIONS, HEAD, PATCH, TRACE and CONNECT.

`request.body`
:   The full content of the REST request associated with this event, if enabled. This contains the HTTP request body. The body is escaped as a string value according to the JSON RFC 4627.


### Audit event attributes of the `transport` event type [_audit_event_attributes_of_the_transport_event_type]

The events with `event.type` equal to `transport` have one of the following `event.action` attribute values: `authentication_success`, `anonymous_access_denied`, `authentication_failed`, `realm_authentication_failed`, `access_granted`, `access_denied`, `run_as_granted`, `run_as_denied`, or `tampered_request`. These events also have the following extra attributes (in addition to the common ones):

`action`
:   The name of the transport action that was executed. This is like the URL for a REST request.

`indices`
:   The indices names array that the request associated with this event pertains to (when applicable).

`request.name`
:   The name of the request handler that was executed.


### Audit event attributes of the `ip_filter` event type [_audit_event_attributes_of_the_ip_filter_event_type]

The events with `event.type` equal to `ip_filter` have one of the following `event.action` attribute values: `connection_granted` or `connection_denied`. These events also have the following extra attributes (in addition to the common ones):

`transport_profile`
:   The transport profile the request targeted.

`rule`
:   The [IP filtering](docs-content://deploy-manage/security/ip-traffic-filtering.md) rule that denied the request.


### Audit event attributes of the `security_config_change` event type [security-config-change]

The events with the `event.type` attribute equal to `security_config_change` have one of the following `event.action` attribute values: `put_user`, `change_password`, `put_role`, `put_role_mapping`, `change_enable_user`, `change_disable_user`, `put_privileges`, `create_apikey`, `delete_user`, `delete_role`, `delete_role_mapping`, `invalidate_apikeys`, `delete_privileges`, `change_apikey`, or `change_apikeys`.

These events also have **one** of the following extra attributes (in addition to the common ones), which is specific to the `event.type` attribute. The attribute’s value is a nested JSON object:

`put`
:   The object representation of the security config that is being created, or the overwrite of an existing config. It contains the config for a `user`, `role`, `role_mapping`, or for application `privileges`.

`delete`
:   The object representation of the security config that is being deleted. It can be the config for a `user`, `role`, `role_mapping` or for application `privileges`.

`change`
:   The object representation of the security config that is being changed. It can be the `password`, `enable` or `disable`, config object for native or built-in users. If an API key is updated, the config object will be an `apikey`.

`create`
:   The object representation of the new security config that is being created. This is currently only used for API keys auditing. If the API key is created using the [create API key API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-create-api-key) it only contains an `apikey` config object. If the API key is created using the [grant API key API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-grant-api-key) it also contains a `grant` config object.

`invalidate`
:   The object representation of the security configuration that is being invalidated. The only config that currently supports invalidation is `apikeys`, through the [invalidate API key API](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-invalidate-api-key).

The schemas of the security config objects mentioned above are as follows. They are very similar to the request bodies of the corresponding security APIs.

`user`
:   An object like:

    ```js
    `{"name": <string>, "enabled": <boolean>, "roles": <string_list>,
    "full_name": <string>, "email": <string>, "has_password": <boolean>,
    "metadata": <object>}`.
    ```

    The `full_name`, `email` and `metadata` fields are omitted if empty.


`role`
:   An object like:

    ```js
    `{"name": <string>, "role_descriptor": {"cluster": <string_list>, "global":
    {"application":{"manage":{<string>:<string_list>}}}, "indices": [                             {"names": <string_list>, "privileges": <string_list>, "field_security":
    {"grant": <string_list>, "except": <string_list>}, "query": <string>,
    "allow_restricted_indices": <boolean>}], "applications":[{"application": <string>,
    "privileges": <string_list>, "resources": <string_list>}], "run_as": <string_list>,
    "metadata": <object>}}`.
    ```

    The `global`, `field_security`, `except`, `query`, `allow_restricted_indices` and `metadata` fields are omitted if empty.


`role_mapping`
:   An object like:

    ```js
    `{"name": <string>, "roles": <string_list>, "role_templates": [{"template": <string>,
    "format": <string>}], "rules": <object>, "enabled": <boolean>, "metadata": <object>}`.
    ```

    The `roles` and `role_templates` fields are omitted if empty. The `rules` object has a recursively nested schema, identical to what is passed in the [API request for mapping roles](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/mapping-users-groups-to-roles.md).


`privileges`
:   An array of objects like:

    ```js
    `{"application": <string>, "name": <string>, "actions": <string_list>,
    "metadata": <object>}`.
    ```


`password`
:   A simple object like:

    ```js
    `{"user":{"name": <string>}}`
    ```


`enable`
:   A simple object like:

    ```js
    `{"user":{"name": <string>}}`
    ```


`disable`
:   A simple object like:

    ```js
    `{"user":{"name": <string>}}`
    ```


`apikey`
:   An object like:

    ```js
    `{"id": <string>, "name": <string>, "expiration": <string>, "role_descriptors": [<object>],
    "metadata": [<object>]}`
    ```

    The `role_descriptors` objects have the same schema as the `role_descriptor` object that is part of the above `role` config object.


The object for an API key update will differ in that it will not include a `name`.

`grant`
:   An object like:

    ```js
    `{"type": <string>, "user": {"name": <string>, "has_password": <boolean>},
    "has_access_token": <boolean>}`
    ```


`apikeys`
:   An object like:

    ```js
    `{"ids": <string_list>, "name": <string>, "owned_by_authenticated_user":
    <boolean>, "user":{"name": <string>, "realm": <string>}}`
    ```

    The object for a bulk API key update will differ in that it will not include `name`, `owned_by_authenticated_user`, or `user`. Instead, it may include `metadata` and `role_descriptors`, which have the same schemas as the fields in the `apikey` config object above.


`service_token`
:   An object like:

    ```js
    `{"namespace":<string>,"service":<string>,"name":<string>}`
    ```

### Extra audit event attributes for specific events [_extra_audit_event_attributes_for_specific_events]

There are a few events that have some more attributes in addition to those that have been previously described:

* `authentication_success`:

    `realm`
    :   The name of the realm that successfully authenticated the user. If authenticated using an API key, this is the special value of `_es_api_key`. This is a shorthand attribute for the same information that is described by the `user.realm`, `user.run_by.realm` and `authentication.type` attributes.

    `user.name`
    :   The name of the *effective* user. This is usually the same as the *authenticated* user, but if using the [run as authorization functionality](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/submitting-requests-on-behalf-of-other-users.md) this instead denotes the name of the *impersonated* user. If authenticated using an API key, this is the name of the API key owner. If authenticated using a service account token, this is the service account principal, i.e. `namespace/service_name`.

    `user.realm`
    :   Name of the realm to which the *effective* user belongs. If authenticated using an API key, this is the name of the realm to which the API key owner belongs.

    `user.run_by.name`
    :   This attribute is present only if the request is using the [run as authorization functionality](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/submitting-requests-on-behalf-of-other-users.md) and denotes the name of the *authenticated* user, which is also known as the *impersonator*.

    `user.run_by.realm`
    :   Name of the realm to which the *authenticated* (*impersonator*) user belongs. This attribute is provided only if the request uses the [run as authorization functionality](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/submitting-requests-on-behalf-of-other-users.md).

    `authentication.type`
    :   Method used to authenticate the user. Possible values are `REALM`, `API_KEY`, `TOKEN`, `ANONYMOUS` or `INTERNAL`.

    `apikey.id`
    :   API key ID returned by the [create API key](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-create-api-key) request. This attribute is only provided for authentication using an API key.

    `apikey.name`
    :   API key name provided in the [create API key](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-create-api-key) request. This attribute is only provided for authentication using an API key.

    `authentication.token.name`
    :   Name of the [service account](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/service-accounts.md) token. This attribute is only provided for authentication using a service account token.

    `authentication.token.type`
    :   Type of the [service account](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/service-accounts.md) token. This attribute is only provided for authentication using a service account token.

* `authentication_failed`:

    `user.name`
    :   The name of the user that failed authentication. If the request authentication token is invalid or unparsable, this information might be missing.

    `authentication.token.name`
    :   Name of the [service account](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/service-accounts.md) token. This attribute is only provided for authentication using a service account token. If the request authentication token is invalid or unparsable, this information might be missing.

    `authentication.token.type`
    :   Type of the [service account](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/service-accounts.md) token. This attribute is only provided for authentication using a service account token. If the request authentication token is invalid or unparsable, this information might be missing.

* `realm_authentication_failed`:

    `user.name`
    :   The name of the user that failed authentication.

    `realm`
    :   The name of the realm that rejected this authentication. **This event is generated for each consulted realm in the chain.**

* `run_as_denied` and `run_as_granted`:

    `user.roles`
    :   The role names as an array of the *authenticated* user which is being granted or denied the *impersonation* action. If authenticated as a [service account](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/service-accounts.md), this is always an empty array.

    `user.name`
    :   The name of the *authenticated* user which is being granted or denied the *impersonation* action.

    `user.realm`
    :   The realm name that the *authenticated* user belongs to.

    `user.run_as.name`
    :   The name of the user as which the *impersonation* action is granted or denied.

    `user.run_as.realm`
    :   The realm name of that the *impersonated* user belongs to.

* `access_granted` and `access_denied`:

    `user.roles`
    :   The role names of the user as an array. If authenticated using an API key, this contains the role names of the API key owner. If authenticated as a [service account](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/service-accounts.md), this is always an empty array.

    `user.name`
    :   The name of the *effective* user. This is usually the same as the *authenticated* user, but if using the [run as authorization functionality](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/submitting-requests-on-behalf-of-other-users.md) this instead denotes the name of the *impersonated* user. If authenticated using an API key, this is the name of the API key owner.

    `user.realm`
    :   Name of the realm to which the *effective* user belongs. If authenticated using an API key, this is the name of the realm to which the API key owner belongs.

    `user.run_by.name`
    :   This attribute is present only if the request is using the [run as authorization functionality](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/submitting-requests-on-behalf-of-other-users.md) and denoted the name of the *authenticated* user, which is also known as the *impersonator*.

    `user.run_by.realm`
    :   This attribute is present only if the request is using the [run as authorization functionality](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/submitting-requests-on-behalf-of-other-users.md) and denotes the name of the realm that the *authenticated* (*impersonator*) user belongs to.

    `authentication.type`
    :   Method used to authenticate the user. Possible values are `REALM`, `API_KEY`, `TOKEN`, `ANONYMOUS` or `INTERNAL`.

    `apikey.id`
    :   API key ID returned by the [create API key](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-create-api-key) request. This attribute is only provided for authentication using an API key.

    `apikey.name`
    :   API key name provided in the [create API key](https://www.elastic.co/docs/api/doc/elasticsearch/operation/operation-security-create-api-key) request. This attribute is only provided for authentication using an API key.

    `authentication.token.name`
    :   Name of the [service account](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/service-accounts.md) token. This attribute is only provided for authentication using a service account token.

    `authentication.token.type`
    :   Type of the [service account](docs-content://deploy-manage/users-roles/cluster-or-deployment-auth/service-accounts.md) token. This attribute is only provided for authentication using a service account token.
