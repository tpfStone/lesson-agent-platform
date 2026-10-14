let epoch = 0;
export const privateContextEpoch = () => epoch;
export const invalidatePrivateContext = () => { epoch++; };
