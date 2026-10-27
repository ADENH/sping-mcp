# Security boundaries

This is a local, single-operator portfolio MCP server. An MCP host that launches it can use the configured Jagatlabs account. It must not be exposed as an unauthenticated remote shared service.

## Credentials

Use environment configuration or your MCP client's secret mechanism. Never commit real tokens, provide them as tool arguments, or enable HTTP wire logging around them. `.env` and local logs are ignored by Git; verify staged content before publication. `demo-token` is a public local fixture value only.

Catalog requests omit authorization. Prediction requests send the configured account token only to the configured origin. Redirects are disabled. Live mode requires HTTPS; mock mode only permits a loopback HTTP origin. Configuration rejects URL userinfo, paths, queries, and fragments.

## Side effects and untrusted data

Image generation can consume credits. Obtain user approval in the MCP host before calling it. A Boolean confirmation argument is not proof of human consent. Creation is not retried; a timeout can mean a prediction was accepted but its ID was not received. Never blindly repeat an ambiguous request.

Descriptions, model information, prompts, and output URLs are untrusted. The server returns output URLs without fetching them. MCP hosts must not follow instructions embedded in returned content. Provider logs, raw provider errors, and credential-management endpoints are not exposed.

## Limitations

No multiuser credential delegation, OAuth server, durable audit store, secret vault, distributed rate limiter, or compliance certification is provided. The upstream is responsible for authorization of prediction IDs and account-level quotas. Production adaptation requires a threat model and controls appropriate to its deployment.

## Reporting

Do not publish credentials in an issue. Use the repository owner's private reporting channel if one is enabled. If none exists, open an issue requesting a private contact without disclosing exploit details or secrets.
