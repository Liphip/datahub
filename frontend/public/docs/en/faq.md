# FAQ

## Is `/docs` part of the app frontend code?

No. `/docs` is a standalone static documentation site served separately from the React route tree.

## Can we host docs elsewhere?

Yes. Copy `frontend/public/docs` to any static host.

## How do we add a new language?

Create a language folder (for example `/fr`), add `README.md`, `_sidebar.md`, `_navbar.md`, and translated pages.

## Where should operational procedures live?

Put reusable procedures here in docs runbooks and cross-link to team-specific internal SOPs if needed.

## Should we store secrets in docs examples?

Never. Use placeholders and reference secret management policy.
