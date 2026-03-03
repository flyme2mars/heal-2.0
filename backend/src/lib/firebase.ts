import admin from "firebase-admin";
import { getApp, getApps, initializeApp } from "firebase-admin/app";

/**
 * Initializes the Firebase Admin SDK.
 * Expects GOOGLE_APPLICATION_CREDENTIALS to be set in the environment.
 */
export function getFirebaseAdmin() {
  if (getApps().length > 0) {
    return getApp();
  }

  return initializeApp({
    // In local development, it picks up credentials from the environment variable
    // In Vercel, you would paste the service account JSON into an environment variable
    credential: process.env.FIREBASE_SERVICE_ACCOUNT 
      ? admin.credential.cert(JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT))
      : undefined, 
  });
}

export async function verifyAppCheck(token: string | undefined) {
  if (!token) {
    throw new Error("Missing App Check token");
  }

  try {
    const appCheck = admin.appCheck(getFirebaseAdmin());
    const decodedToken = await appCheck.verifyToken(token);
    return decodedToken;
  } catch (error) {
    console.error("Firebase App Check verification failed:", error);
    throw new Error("Invalid App Check token");
  }
}
