# 6. Access, roles and sharing

**What it is for.** Who can sign in, what each role unlocks, who may read a given conversation, and how a
conversation is shared. Every other part of the product sits behind it.

**Who uses it.** Everyone: staff asking questions within authorized areas, administrators running knowledge
areas, and automation or AI coding tools connecting on a company account or a machine key.

## What you can do

| Capability | What happens |
| --- | --- |
| **Sign in with the company account** | People reach Yvoke through the company's Microsoft sign-in; there is no separate Yvoke password. Administrators land on the admin console, everyone else on chat, and a deep link takes you where you originally asked for. |
| **Sign out** | Ends the session for good and shows a confirmation page. Returning requires signing in again — closing the browser alone is not the same as signing out. |
| **Simulated sign-in for demos and local testing** | Test and demo installations can offer simulated sign-in supporting Global User, Superadmin, dynamic per-area roles (`<Area>.User`, `<Area>.Admin`), and multi-role selection. It cannot be switched on for the live service. |
| **Roles come from the company directory** | Four logical role levels are derived from Microsoft Entra ID `roles` claims: Superadmin (`Admin`), Global User (`User`), Area Admin (`<Area>.Admin`), and Area User (`<Area>.User`). Multi-role union allows combining roles, while users without recognized role claims are denied by default. |
| **Conversations are private by default** | A conversation belongs to whoever started it. Nobody else sees it, and nobody else can open it — unless the owner shares it, or an administrator deliberately opens it through the admin console. |
| **Share with colleagues in an area** | Adding the folder tag `public` publishes the conversation to users authorized for that conversation's knowledge area, in a *Public Conversations* folder. Removing the tag un-shares it immediately. |
| **Read-only view of a shared conversation** | A viewer sees the whole history with a *Shared* badge and a banner saying it is read-only. The message box, tag controls and delete button are hidden. |
| **Viewers can reveal reasoning for themselves** | Someone reading a shared conversation can toggle *Show thinking & tools*. The choice applies only to them and never changes the owner's settings. |
| **Administrators inspect area conversations** | Superadmins can inspect all conversations; Area Admins can review user conversations, message feedback, and execution traces strictly within their administered areas. |
| **Administrators unlock the admin console** | Knowledge areas, documents, records, the graph, playbooks, profiles, prompts, imports, connectors, agent runs, jobs, user conversations, search testing, logs, and feedback. Superadmins also access cost monitoring, model pricing, audit trail, area creation/deletion, and global prompt activation. Regular users see none of it. |
| **Access from AI coding tools** | An agentic IDE connects on the same company account and reads knowledge areas authorized for that user. Setup is the server address plus a sign-in. |
| **Machine access for automation** | Scripts authenticate with a global machine key (`APP_SECURITY_API_KEY`) or per-area machine keys (`APP_SECURITY_AREA_API_KEYS`). Keys unlock importing content, running jobs, and deleting documents within their authorized area. |
| **Chat can be switched off** | When web chat is off, chat pages say so while the admin console, the AI-client connection and the automation interfaces keep working. Useful during incidents or cost freezes. |

## How it behaves

- **Administrators may read, never write.** An administrator opening someone else's conversation cannot
  post, rate, stop, retag or delete. Nothing an administrator does can alter another person's history.
- **Sharing is scoped to the conversation's area.** Adding `public` publishes the conversation only to
  users authorized for that area. Unscoped conversations (`area IS NULL`) are visible to any authenticated
  user until locked to an area on turn 1.
- **⚠ `public` is a reserved folder name.** Folder names are free text, so typing "public" as an ordinary
  folder name **silently shares that conversation with users authorized for that area**. Nothing warns the user.
- **A shared conversation publishes its other folder names.** Once shared, its remaining folder names
  appear in folder-name suggestions for users with access to that area.
- **Automation credentials always win.** If an administrator uses a tool that carries the machine key,
  that work runs as the machine and loses administrator rights — which looks like a permissions bug.
- **Connector imports are administrator-only.** A regular user or machine-key caller is refused.
- **⚠ Conversation access is not audited.** The audit trail records content actions such as imports and
  deletions. **Opening or reading another person's conversation leaves no record anywhere.**
- **Refusals are opaque ("Not Found").** Attempting to open, export, or trace a conversation, citation,
  document, playbook, or prompt belonging to an unauthorized area returns an opaque *not found* (`404`),
  never disclosing whether the target resource exists. List endpoints silently filter out unauthorized items.
- **Desktop app access is owner-only and area-scoped.** It lists and opens only conversations you own.
  Area synchronization (`/api/chat/v1/areas`) lists only areas the user is authorized to access, and
  downloading an area package for an unauthorized area returns `404 Not Found`.
- **Machine keys are partitioned per area.** External automation scripts can use area-specific API keys
  configured in `APP_SECURITY_AREA_API_KEYS`. A per-area key cannot write to, inspect jobs in, or delete
  documents from any other area. Constant-time key comparison prevents timing leaks.
- **Playbooks, profiles, and prompts are area-scoped.** Users and connected AI clients can discover and
  read only playbooks and profiles belonging to areas they are authorized to access.

## Limits

- **The sidebar loads only the 100 most recent conversations you can see.** No paging control, no search.
- **Sends are capped per person**, 20 per minute by default.
- **A limited number of live answers run at once** (64 by default). When full the user is asked to retry —
  but the question has already been saved, so the thread shows a question with no answer. That ceiling
  covers only the live word-by-word mode, so the effective limit depends on which mode people use.
- **All automation shares one machine key**, therefore one send budget and one identity. A busy script can
  rate-limit every other integration, and reporting cannot tell them apart.
- **Every signed-in user can read how the assistant is configured.** The playbook library, the
  specialist profiles and the base instructions are readable by any account holding an access token —
  the desktop app and connected AI clients need them — with no administrator check. So how Yvoke works
  is visible to everyone who can sign in, not only what it knows. The machine key, by contrast, cannot
  reach them at all: it is confined to importing, jobs and documents.
- **The offered models, the chat on/off switch, the per-user question limit, the capacity ceiling and the
  number of simultaneous imports are set when the system is deployed** — not editable by an administrator
  in the product. Changing any of them is a deployment change.
- **The *Public Conversations* count counts a conversation once per folder it sits in**, so the number can
  exceed the number of distinct shared conversations.
- **Rating buttons are shown to viewers of a shared conversation**, but any rating they submit is refused
  and the widget silently keeps its previous state.
- **Sharing has no expiry and no viewer list.** It stays visible until the owner removes the tag, and the
  owner cannot see who has read it.
- **Authorization is enforced at the Area boundary.** Permissions are granted per area. A user authorized
  for an area has access to all collections, documents, and playbooks within that area; there are no
  per-collection or per-document ACLs within an area.
- **Automation keys cannot access chat or admin consoles.** Machine keys grant ingestion and job status
  access only; they cannot initiate chat sessions or access web admin interfaces.

## Not supported

- Sharing with one person, a named group, or via an external link.
- Any user management inside Yvoke — no invite, no role change, no deactivation. Every account is
  nevertheless listed by name in the spend filter (chapter 8) — by email address where no name is
  stored — but only as something to filter a report by; there is nothing there to act on.
- Signing in with anything but the company account. No local password, self-registration or reset.
- Read receipts, share notifications, or a "who viewed this" list.
- Restricting access to individual collections or documents within an authorized area (permissions apply to the whole area).
- User self-service role requests or managing user roles inside Yvoke. Roles are managed centrally in Microsoft Entra ID.
- Cross-area user conversations or sharing a conversation across area boundaries.
- An administrator editing or moderating content inside another person's conversation.
- Transferring ownership of a conversation, or keeping a leaver's conversations accessible after their
  account goes.

---
