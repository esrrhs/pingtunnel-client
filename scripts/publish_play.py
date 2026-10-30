#!/usr/bin/env python3
import os
import sys
import socket
socket.setdefaulttimeout(600)
from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

KEY_FILE = '/root/gen-lang-client-0734706836-cbe66d776b80.json'
PACKAGE_NAME = 'com.esrrhs.pingtunnel.client'
SCOPES = ['https://www.googleapis.com/auth/androidpublisher']

ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AAB_PATH = os.path.join(ROOT_DIR, 'app/build/outputs/bundle/release/app-release.aab')
WHATSNEW_ZH = os.path.join(ROOT_DIR, 'whatsnew/whatsnew-zh-CN')
WHATSNEW_EN = os.path.join(ROOT_DIR, 'whatsnew/whatsnew-en-US')

def main():
    if not os.path.exists(KEY_FILE):
        print(f"Error: Key file not found: {KEY_FILE}", file=sys.stderr)
        sys.exit(1)

    if not os.path.exists(AAB_PATH):
        print(f"Error: AAB file not found: {AAB_PATH}", file=sys.stderr)
        sys.exit(1)

    with open(WHATSNEW_ZH, 'r', encoding='utf-8') as f:
        notes_zh = f.read().strip()
    with open(WHATSNEW_EN, 'r', encoding='utf-8') as f:
        notes_en = f.read().strip()

    print(f"==> Authenticating with service account: {KEY_FILE}")
    credentials = service_account.Credentials.from_service_account_file(KEY_FILE, scopes=SCOPES)
    service = build('androidpublisher', 'v3', credentials=credentials)

    print(f"==> Creating edit for package: {PACKAGE_NAME}")
    edit_req = service.edits().insert(packageName=PACKAGE_NAME, body={})
    edit = edit_req.execute()
    edit_id = edit['id']
    print(f"==> Edit ID created: {edit_id}")

    try:
        print(f"==> Uploading bundle: {AAB_PATH} ({os.path.getsize(AAB_PATH) / 1024 / 1024:.2f} MB)")
        media = MediaFileUpload(AAB_PATH, mimetype='application/octet-stream', resumable=True)
        bundle_resp = service.edits().bundles().upload(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            media_body=media
        ).execute()

        version_code = bundle_resp.get('versionCode')
        print(f"==> Bundle uploaded successfully! VersionCode: {version_code}")

        release_body = {
            'releases': [{
                'name': '0.1.0',
                'versionCodes': [str(version_code)],
                'status': 'completed',
                'releaseNotes': [
                    {'language': 'zh-CN', 'text': notes_zh},
                    {'language': 'en-US', 'text': notes_en},
                ]
            }]
        }

        print("==> Updating track: production")
        track_resp = service.edits().tracks().update(
            packageName=PACKAGE_NAME,
            editId=edit_id,
            track='production',
            body=release_body
        ).execute()
        print(f"==> Track updated: {track_resp.get('track')}")

        print("==> Committing edit to Google Play (sent for review automatically)...")
        commit_resp = service.edits().commit(
            packageName=PACKAGE_NAME,
            editId=edit_id
        ).execute()
        print(f"==> Successfully committed! Commit ID: {commit_resp.get('id')}")

    except Exception as e:
        print(f"==> Error during publish: {e}", file=sys.stderr)
        try:
            print("==> Deleting edit...")
            service.edits().delete(packageName=PACKAGE_NAME, editId=edit_id).execute()
        except Exception:
            pass
        sys.exit(1)

if __name__ == '__main__':
    main()
