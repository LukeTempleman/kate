# Portal e2e test
```
cd portal/test && npm i puppeteer
KATE_TOKEN=$(cat ../.token) node e2e.js
```
Real Chrome against the live worker; speech APIs stubbed, everything else real.
