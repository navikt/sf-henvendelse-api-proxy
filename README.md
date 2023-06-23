# sf henvendelse api proxy
Proxy for apper i nais for att nå henvendelse api i Salesforce.

Typisk autentisering sker med azure on-behalf-of token utstellt pa proxyn

Godkjent att bruke machine token mot ikke-sensitiva endepunkter (kodeverk).

Tillater också nais token med servicebruker for en legacy koppling (bisys) via legacy-proxyn
henvendelse-api-dialogv1. Den kopplingen ønsker vi avvekla etterhvart
