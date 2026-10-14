let currentHeaderName = "X-XSRF-TOKEN";
let currentToken = "";

export function setCsrfToken(headerName: string, token: string): void {
  currentHeaderName = headerName || "X-XSRF-TOKEN";
  currentToken = token || "";
}

export function clearCsrfToken(): void {
  currentToken = "";
}

export function csrfHeaders(): Record<string, string> {
  return currentToken
    ? { [currentHeaderName]: currentToken }
    : {};
}