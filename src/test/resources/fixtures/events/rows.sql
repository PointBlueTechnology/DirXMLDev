-- The store contract's example rows (docs/store.md of DirXMLEventLogger 2.0.0), plus two PolicyLogger
-- rows and one schema-version-1 row, for bin/smoke-events.sh. Fictional people; the tree is SYNTH.
INSERT INTO dxmlevent (eventid, classname, srcdn, srcentryid, eventtype, eventjson, xmlevent, cachedtime, srcdriver, schemaversion) VALUES
('synth#1', 'User', E'\\SYNTH\\data\\people\\jdoe', '35868', 'add',
 '{"event-type":"add","schemaVersion":2,"class-name":"User","event-id":"synth#1","src-dn":"\\\\SYNTH\\\\data\\\\people\\\\jdoe","src-entry-id":"35868","association":{"state":"pending","value":"jdoe"},"attributes":{"Surname":{"type":"string","value":"Doe"},"Given Name":{"type":"string","value":"John"},"Telephone Number":[{"type":"teleNumber","value":"555-0100"},{"type":"teleNumber","value":"555-0101"}],"Description":"plain value"},"password":"***"}',
 E'<nds dtdversion="4.0"><input><add class-name="User" event-id="synth#1" src-dn="\\SYNTH\\data\\people\\jdoe" src-entry-id="35868"><association state="pending">jdoe</association><add-attr attr-name="Surname"><value type="string">Doe</value></add-attr><add-attr attr-name="Given Name"><value type="string">John</value></add-attr><password>***</password></add></input></nds>',
 '2026-10-07 14:15:02+00', E'\\SYNTH\\synth\\SynthSet\\Loop', 2),
('synth#2', 'User', E'\\SYNTH\\data\\people\\jdoe', '35868', 'modify',
 '{"event-type":"modify","schemaVersion":2,"class-name":"User","event-id":"synth#2","src-dn":"\\\\SYNTH\\\\data\\\\people\\\\jdoe","src-entry-id":"35868","association":{"state":"associated","value":"jdoe"},"attributes":{"Given Name":{"remove-values":[{"type":"string","value":"Johnny"}],"add-values":[{"type":"string","value":"John"}]},"Telephone Number":{"remove-all-values":true,"add-values":[{"type":"teleNumber","value":"555-0102"}]}}}',
 E'<nds dtdversion="4.0"><input><modify class-name="User" event-id="synth#2" src-dn="\\SYNTH\\data\\people\\jdoe"><modify-attr attr-name="Given Name"><remove-value><value type="string">Johnny</value></remove-value><add-value><value type="string">John</value></add-value></modify-attr></modify></input></nds>',
 '2026-10-07 14:16:00+00', E'\\SYNTH\\synth\\SynthSet\\Loop', 2),
('synth#3', 'User', E'\\SYNTH\\data\\people\\jdoe', '35868', 'delete',
 '{"event-type":"delete","schemaVersion":2,"class-name":"User","event-id":"synth#3","src-dn":"\\\\SYNTH\\\\data\\\\people\\\\jdoe","src-entry-id":"35868","association":{"state":"associated","value":"jdoe"}}',
 NULL, '2026-10-07 14:17:00+00', E'\\SYNTH\\synth\\SynthSet\\Loop', 2),
('synth#5', 'User', E'\\SYNTH\\data\\people\\john.doe', '35868', 'rename',
 '{"event-type":"rename","schemaVersion":2,"class-name":"User","event-id":"synth#5","src-dn":"\\\\SYNTH\\\\data\\\\people\\\\john.doe","old-src-dn":"\\\\SYNTH\\\\data\\\\people\\\\jdoe","remove-old-name":"true","new-name":"john.doe"}',
 E'<nds><input><rename class-name="User" event-id="synth#5" src-dn="\\SYNTH\\data\\people\\john.doe" old-src-dn="\\SYNTH\\data\\people\\jdoe"><new-name>john.doe</new-name></rename></input></nds>',
 '2026-10-07 14:19:00+00', E'\\SYNTH\\synth\\SynthSet\\Loop', 2),
('synth#6', 'User', E'\\SYNTH\\data\\staff\\john.doe', '35868', 'move',
 '{"event-type":"move","schemaVersion":2,"class-name":"User","event-id":"synth#6","src-dn":"\\\\SYNTH\\\\data\\\\staff\\\\john.doe","old-src-dn":"\\\\SYNTH\\\\data\\\\people\\\\john.doe","parent":{"src-dn":"\\\\SYNTH\\\\data\\\\staff","src-entry-id":"40001"}}',
 E'<nds><input><move class-name="User" event-id="synth#6" src-dn="\\SYNTH\\data\\staff\\john.doe"><parent src-dn="\\SYNTH\\data\\staff"/></move></input></nds>',
 '2026-10-07 14:20:00+00', E'\\SYNTH\\synth\\SynthSet\\Loop', 2),
('synth#7', 'Group', E'\\SYNTH\\data\\groups\\admins', '40100', 'modify',
 '{"event-type":"modify","schemaVersion":2,"class-name":"Group","event-id":"synth#7","src-dn":"\\\\SYNTH\\\\data\\\\groups\\\\admins","attributes":{"Member":{"add-values":[{"type":"dn","value":"\\\\SYNTH\\\\data\\\\staff\\\\john.doe"}]}}}',
 E'<nds><input><modify class-name="Group" event-id="synth#7" src-dn="\\SYNTH\\data\\groups\\admins"><modify-attr attr-name="Member"><add-value><value type="dn">\\SYNTH\\data\\staff\\john.doe</value></add-value></modify-attr></modify></input></nds>',
 '2026-10-07 14:21:00+00', E'\\SYNTH\\synth\\SynthSet\\Loop', 2);
-- the same engine event logged by a policy of another driver, input and output
INSERT INTO dxmlevent (eventid, classname, srcdn, srcentryid, eventtype, eventjson, xmlevent, cachedtime, srcdriver, channel, policy, stage, schemaversion) VALUES
('synth#2', 'User', E'\\SYNTH\\data\\people\\jdoe', '35868', 'modify', '{"event-type":"modify","schemaVersion":2,"class-name":"User","event-id":"synth#2","attributes":{"Given Name":{"add-values":[{"type":"string","value":"John"}]}}}',
 E'<nds><input><modify class-name="User" event-id="synth#2" src-dn="\\SYNTH\\data\\people\\jdoe"><modify-attr attr-name="Given Name"><add-value><value type="string">John</value></add-value></modify-attr></modify></input></nds>',
 '2026-10-07 14:16:01+00', E'\\SYNTH\\synth\\SynthSet\\Loop', 'subscriber', 'sub-ctp-Normalize', 'input', 2),
('synth#2', 'User', E'\\SYNTH\\data\\people\\jdoe', '35868', 'modify', '{"event-type":"modify","schemaVersion":2,"class-name":"User","event-id":"synth#2","attributes":{"givenName":{"add-values":[{"type":"string","value":"John"}]}}}',
 E'<nds><input><modify class-name="User" event-id="synth#2" src-dn="\\SYNTH\\data\\people\\jdoe"><modify-attr attr-name="givenName"><add-value><value type="string">John</value></add-value></modify-attr></modify></input></nds>',
 '2026-10-07 14:16:02+00', E'\\SYNTH\\synth\\SynthSet\\Loop', 'subscriber', 'sub-ctp-Normalize', 'output', 2);
-- a row from before 2.0.0: no schemaVersion key, add-values as whole-element text
INSERT INTO dxmlevent (eventid, classname, srcdn, srcentryid, eventtype, eventjson, xmlevent, cachedtime, srcdriver, schemaversion) VALUES
('synth#0', 'User', E'\\SYNTH\\data\\people\\old', '100', 'modify', '{"event-type":"modify","class-name":"User","attributes":{"Title":{"add-values":["Clerk"]}}}', NULL, '2026-09-01 10:00:00+00', E'\\SYNTH\\synth\\SynthSet\\Loop', 1);
