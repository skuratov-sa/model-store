# Addresses and regions

The `modern` API keeps `GET /address/regions`, `GET /address`, `POST /address`,
`PUT /address/{addressId}`, and `DELETE /address/{addressId}`. An authenticated
actor's participant ID determines ownership; a foreign or deleted address
returns `404 ADDRESS_NOT_FOUND` on update and delete. Deletion removes the
participant link and marks the address `DELETED`, preserving order references.
The distinct country list follows the legacy database query and may contain
null or countries from deleted addresses.

Ordering should inject `DeliveryAddressReadPort` and call
`findActiveOwned(customerId, addressId)` inside its order transaction. This
returns only an active address linked to that customer, without exposing a JPA
association or the address table directly to ordering.

`GET /address/regions` requires authentication in both legacy and modern
runtimes. The legacy allowlist contains `/regions`, a different path.

Integration step if product requirements later make regions public: in
`ModernPlatformConfiguration.securityFilterChain`, add
`requestMatchers(HttpMethod.GET, "/address/regions").permitAll()` before
`.anyRequest().authenticated()`. The existing configuration authenticates
this route.
