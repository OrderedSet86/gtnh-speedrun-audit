# Prompt: a cut-first workflow for the Narrative prose skills

You work in the Narrative repository (`github.com/OrderFromChaos/narrative`). Change the workflow of the prose skills
(`nar:page`, the category skills `nar:proposal`, `nar:brief` and `nar:runbook`, and the **Published prose** part of
`nar:core`), so that an agent who cleans up an existing page cuts and moves content before the agent fixes a sentence.
Keep the rules that exist. Change the order of the steps, add the steps below, and add a check to `verify.py` where a
check is possible.

## Problem

The skills produce good new pages, and weak cleanups of existing pages. The rules for cuts are in `nar:page` already:
R13-reader-knows ("Leave out what the reader knows already, and detail the reader can't use") and R13-section-use ("Name
the reader's use of each section"). The steps don't make the agent apply them:

1. The steps run `verify.py` first, and the agent fixes the findings first. When the page exists, the findings are
   sentences, so the agent's work goes into rewording, and the agent doesn't consider a cut.
2. A page that passes the sentence checks reads as done to the agent. R13-whole-page is the rule that each sentence of
   the page reads well, and an agent takes the rule as "fix each sentence". Text that should leave the page gets reworded
   instead.
3. `verify.py` doesn't check a rule of `nar:page`. The review lists are all sentence lists, so the agent doesn't get a
   prompt for a cut.
4. A step for a move doesn't exist. A section that a reader can't use is often right for another reader, and the agent
   needs a step to name the page for that reader.

## Evidence from one session

Three pages of a Minecraft mod repository (`gtnh-speedrun-audit`), each cleaned with `nar:page`:

| Page | First pass | Pass after the user asked for cuts |
|---|---|---|
| README.md (144 lines) | 144 lines. The agent fixed each sentence, and didn't cut a line | 55 lines for runners. The event table moved to VERIFIERS.md, and the developer material to docs/DEVELOPMENT.md |
| VERIFIERS.md (480 lines and 133 findings) | skipped, because the user asked for cuts | 167 lines for moderators. A 402-line field reference moved to docs/LOG-FORMAT.md for tool writers |

The user's words were "I am quite happy with what narrative does for greenfield additions, but I think it does not cut
nearly aggressively enough after a page is created."

On VERIFIERS.md, about 85% of the text was a field-by-field format reference that the page's reader, a moderator, can't
act on. All 133 findings of the first run were in that reference or in text the agent didn't write. In a sentence-first
pass, the agent would have reworded about 400 lines that belonged on another page.

Two other faults came up in the same pages, and the steps below cover them:

- Notes such as "Bundles before this version…" don't name the version. The agent dated each one from git, and one note
  ("In older bundles, the walk took about 50 ticks") was false for every release.
- In the first rewrite, the agent kept each fact, whether or not the reader needed the fact.

## Workflow to add

Write these steps into `nar:page` for a change to a page that exists, and keep this order. For a new page, keep the
current steps.

1. **Reader and question.** Name the reader and the question of the page (the existing step).
2. **Triage before wording.** Before a rewrite of a sentence, list each section, table and long paragraph of the page.
   Give each one of three labels, and one line with the reason:
   - `keep`: the named reader acts on the content. Name the action.
   - `move`: another reader acts on the content. Name the reader and the destination page, an existing page or a new
     one.
   - `cut`: a reader doesn't act on the content, or the page has the fact in another place.

   Put the triage table in the report to the user. Give each `keep` an action of the named reader. "The reader might
   want to know" isn't an action.
3. **Restructure.** Make the moves and the cuts. Merge the data of one question into one table or chart. Order the kept
   sections by the reader's priorities. Check that each moved fact is on the destination page.
4. **Facts.** Check each kept claim against the source. Find the release or the date of each relative date ("this
   version", "older bundles", "now", "recent") in the project's history, and write the release or the date.
5. **Wording.** Only now run `verify.py`, and take **Prose review** on the kept and the moved text.
6. **Checklist.** Read the page against the checklist of `nar:page`. Add one item: "The report has the triage table, and
   the share of the page that was cut or moved."

## Checks to add to verify.py

- **A section list.** For each page, a sixth review list with each heading, the heading's word count and line count, and
  the question "What does the named reader do with this section?" The agent answers each entry in the report. The other
  lists work the same way.
- **Relative dates.** A finding for "this version", "before this version", "older bundles", "earlier versions" and "the
  current version" without a version number or a date on the same line.
- **Size.** A warning when a page grows in a cleanup pass, or shrinks by less than a small share, so the agent checks
  the triage.

## What to keep

Keep each sentence rule, each existing check and the order of **Writing a comment**. The fault is the order of the steps
for an existing page, and the missing triage step. The sentence rules themselves worked: after the cuts, the same rules
made the kept text read well.

## Done

The skills have the triage step before the wording step for an existing page, `verify.py` writes the section list and
flags relative dates, and the repository's benchmark has a test page. A cleanup of that page by the agent cuts or moves
the text that the named reader can't use.
