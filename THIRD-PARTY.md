# Third-party components

DirXMLDev's own code is under its LICENSE. A release attaches these jars next to `dirxml-dev-<v>.jar`.
Each keeps its own license; the notices below go wherever the jars are redistributed (an image, a
bundle, another product such as the directory-console).

| Component | Version | What for | License |
|---|---|---|---|
| JLDAP, `com.novell.ldap:jldap` | 4.3 | the LDAP client of the vault path: extended operations, the trace over LDAP (docs/vendor-free-vault.md) | The OpenLDAP Public License, <http://www.openldap.org/software/release/license.html> (as its Maven Central POM declares) |
| PostgreSQL JDBC driver, `org.postgresql:postgresql` | 42.7.7 | the Event Logger's store (docs/event-store.md) | BSD-2-Clause |
| `dirxml-trace-core` (DirXMLTraceViewer) | 1.10.0 | `trace.view`: the trace parser (docs/trace-view.md) | Point Blue's own, as the DirXMLTraceViewer repository states |

**Not included, ever:** the NetIQ/OpenText Identity Manager jars (`dirxml.jar`, `dirxml_misc.jar`,
`nxsl.jar` …). The features that run the engine (validate's compile check, simulate, package GCV
checksums) load them from a local `lib/` that you supply (docs/install.md). Talking to a vault
needs none of them.

## Not affiliated with OpenText

DirXMLDev is made by [Point Blue Technology](https://pointbluetech.com). It is not affiliated with,
endorsed by or supported by OpenText. NetIQ, eDirectory, Identity Manager and OpenText are trademarks of
OpenText or its affiliates; other names belong to their owners.
