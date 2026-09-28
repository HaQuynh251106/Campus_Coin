export const environment = {
  production: true,
  // The deployed API. The Static Web App and the App Service are different
  // origins, so production cannot use the relative '/api' that the development
  // proxy handles - see proxy.conf.json, which only exists for `ng serve`.
  // CORS on the backend allows exactly this Static Web App origin.
  apiUrl: 'https://campuscoin-api.azurewebsites.net/api',
  useMockData: false,
  appTitle: 'Campus Coin — Smart Spending, Student Style',
  version: '1.0.0'
};
