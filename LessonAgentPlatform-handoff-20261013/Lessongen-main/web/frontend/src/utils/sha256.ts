import { sha256 } from "js-sha256";

export async function sha256Hex(
  input: string | ArrayBuffer | Uint8Array,
): Promise<string> {
  const bytes =
    typeof input === "string"
      ? new TextEncoder().encode(input)
      : input instanceof Uint8Array
        ? input
        : new Uint8Array(input);

  const subtle = globalThis.crypto?.subtle;

  if (subtle && typeof subtle.digest === "function") {
    const buffer = new ArrayBuffer(bytes.byteLength);
    new Uint8Array(buffer).set(bytes);

    const result = await subtle.digest("SHA-256", buffer);

    return Array.from(new Uint8Array(result))
      .map((value) => value.toString(16).padStart(2, "0"))
      .join("");
  }

  return sha256(bytes);
}