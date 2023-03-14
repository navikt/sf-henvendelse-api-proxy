# sf henvendelse api proxy
Proxy for apper i nais for att nå henvendelse api i Salesforce.

Typisk autentisering sker med azure token utstellt pa proxyn. Det sker da en token exchange for o hente ut token med
audience til Salesforce som brukes der for tilgangskontroll

Godkjent att bruke machine token mot ikke-sensitiva endepunkter (kodeverk).

Tillater också nais token med servicebruker for to legacy kopplingar (bisys, pesys) via legacy-proxyn
henvendelse-api-dialogv1. Den kopplingen ønsker vi avvekla etterhvart