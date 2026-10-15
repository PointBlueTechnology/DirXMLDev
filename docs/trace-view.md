# A trace as transactions (`trace.view`)

`trace.view` reads a driver trace and returns it as **transactions**: for each driver and channel,
everything from one event to the next. It shows what the engine did in each one, so an agent can
find the failing or vetoed event without reading thousands of lines. The MCP server offers it as
`idm.trace.view` ([mcp.md](mcp.md)).

```bash
bin/idm trace.view --file ad.trace                               # every transaction, most recent last
bin/idm trace.view --file ad.trace --status error --json         # those with an error status, as JSON
bin/idm trace.view --file ad.trace --transaction 12              # one transaction's raw text
bin/idm trace.view --env stg --driver "AD Driver" --lines 5000   # the driver's trace file over SSH
bin/idm trace.view --env stg --driver "AD Driver" --ldap --seconds 60   # live events over LDAP
```

## Best effort, by design

A trace is not structured data. The engine writes its own messages, but a driver shim can print
any string it likes, and policies can trace their own messages too. So the view reads **only the
engine's own wording**, taken from the engine's message bundles and the trace viewer's markers:

| What | The engine's wording |
|---|---|
| a subscriber event | `Start transaction.` … `End transaction.` |
| a publisher event | `Receiving DOM document from application.` |
| driver start | `Reading driver information …` |
| a policy set | `Applying … policies.` |
| a policy | `Applying policy: <name>.` (subscriber and publisher channels only) |
| a rule applied | `Applying rule <name>.` |
| an action | `Action: <action>.` |
| a veto | `Action: do-veto(…)`, `Operation vetoed by ….` |
| a status | a `<status level="…">` document, in a returned or submitted document |
| the event | the first operation element (`add`, `modify`, `delete` …) and its `class-name`, `src-dn`/`dest-dn`, `event-id` |

Anything else is **kept as it is and never interpreted**. That includes a shim's own output, a
response body, or a `<status>` word in free text that has no `level`. Each transaction counts its
unrecognized records. Two consequences:

- **An empty status or veto list means none was recognized, not that none happened.** The JSON
  says so in its `note`, and the summary counts transactions under `"none recognized"`.
- **The raw text is always one step away.** Each transaction lists its input lines as
  `lineRanges` (other drivers' records interleave, so there can be several runs), and
  `--transaction N` returns its raw text.

## Grouping

The records come from `dirxml-trace-core`, the parser the desktop viewer and the web share. A
record belongs to its driver and channel (subscriber `ST`, publisher `PT`, service `SST`, or other
for driver-level messages).

- A transaction starts at an event marker and runs to `End transaction.` or the next event on the
  same driver and channel.
- What follows `End transaction.` before the next event stays with the transaction it follows, so
  nothing is dropped.
- If the input begins inside a transaction (a tail), that transaction has no opener; the text view
  shows it as `(no event in view)`.
- A subscriber transaction with no end in the input is reported as unfinished.

## Options

| Option | Meaning |
|---|---|
| `--file F` | a trace file on this machine |
| `--env E --driver D` | the driver's trace from the environment: over SSH, the last `--lines N` (default 2000) or `--since MIN` of its `DirXML-TraceFile`; over LDAP (`--ldap`, or when the environment has no `sshHost`), `--seconds N` (default 30) of live events. It is a bounded read and never follows. |
| `--driver NAME` (with `--file`), `--trace-driver NAME` (with `--env`) | keep one trace name's transactions |
| `--channel subscriber\|publisher\|service\|other` | keep one channel |
| `--status success\|warning\|retry\|error\|fatal` | keep transactions with a status document at that level |
| `--vetoed` | keep transactions with a recognized veto |
| `--text RE` | keep transactions whose raw text matches |
| `--last N` | show the most recent N that match (default 20); the summary covers all of them |
| `--transaction N` | that one transaction, with its raw text (`text`, capped at 200,000 characters) |
| `--json` | the document below; without it, one line per transaction |

## JSON

```json
{
  "source": "file /tmp/ad.trace",
  "lines": 29, "records": 21,
  "note": "best effort: …",
  "summary": {
    "transactions": 5,
    "byDriver": {"AD": {"subscriber": 3, "publisher": 1}, "Loop": {"subscriber": 1}},
    "worstStatus": {"none recognized": 3, "error": 1, "success": 1},
    "vetoed": 1, "unfinished": 1, "outsideTransactions": 0, "unrecognizedRecords": 1,
    "problemTransactions": [2, 4]
  },
  "matched": 5, "shown": 5,
  "transactions": [
    {
      "n": 2, "driver": "AD", "channel": "subscriber", "opener": "Subscriber event",
      "start": "10/15/26 10:01:00.000", "end": "10/15/26 10:01:00.200",
      "lines": [3, 19], "lineRanges": [[3, 9], [11, 19]], "records": 10, "ended": true,
      "event": {"operation": "add", "class": "User", "dn": "\\T\\data\\users\\jdoe", "eventId": "ev1"},
      "policySets": [{"set": "Applying event transformation policies.",
                      "policies": [{"policy": "sub-etp-Veto", "rules": ["Veto if no surname"], "actions": ["do-veto()"]}]}],
      "statuses": [], "worstStatus": null,
      "vetoes": ["Action: do-veto().", "Operation vetoed by policy."],
      "unrecognizedRecords": 1
    }
  ]
}
```

`problemTransactions` lists the transactions with a recognized `retry`, `error` or `fatal` status,
or a recognized veto (the last 100 of them). Lists inside a transaction (rules, actions, statuses,
vetoes) are capped at 40 entries.
