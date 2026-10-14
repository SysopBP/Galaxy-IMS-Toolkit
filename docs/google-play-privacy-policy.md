# Galaxy IMS Toolkit privacy policy

Effective date: October 14, 2026

Galaxy IMS Toolkit is maintained by SysopBP. It is an independent fork of Pixel IMS by Kyujin Cho and contributors. The upstream authors do not operate or support this fork.

## On-device information
Features read carrier/SIM and IMS status, device/software information, selected configuration/XML files, module inventory and privileged-backend state. Settings, observer state and backup-history records are stored on the device. Root, Shizuku and the optional Xposed companion run with their own privileged contexts.

## Network requests
The update-check implementation queries the GitHub API for SysopBP/Galaxy-IMS-Toolkit releases. GitHub receives the network request, including its source IP address and normal request metadata, under [GitHub's privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement). Opening external links or downloading a release uses the selected service/browser's privacy terms. This policy does not describe the app as offline.

The reviewed source has no advertising or analytics integration. Network permission and bundled HTTP libraries must not be interpreted as proof that data cannot leave the device.

## Diagnostics and sharing
The app writes diagnostic messages to Android logs. Diagnostic reports can contain device, carrier, SIM-related or configuration information. Export saves a report to Downloads or a user-selected document destination; the Android share sheet can send it to an application you select. Review and redact reports before sharing. Maintainers receive information you intentionally submit through GitHub or another reporting route.

## Storage, backup and deletion
The application allows Android backup/device transfer with exclusions for app-private files and the ims_observer.xml preference store. Other eligible preferences may be included according to Android/your backup provider's settings. Exported files and root-created configuration/module files are outside normal app-data deletion.

Clear app data or uninstall to remove ordinary app-private data. Remove exported reports/backups separately and review Android backup settings. Uninstalling does not automatically undo carrier changes or remove externally created files.

## Permissions and security
READ_PHONE_STATE supports telephony/IMS status. INTERNET supports update requests. Root/Shizuku/Xposed grants support privileged operations you enable. These permissions can expose sensitive information or affect connectivity; use only the features you intend. No software or network-storage system guarantees complete security.

## Contact and changes
Contact the maintainer through [Galaxy IMS Toolkit issues](https://github.com/SysopBP/Galaxy-IMS-Toolkit/issues). Do not publish personal identifiers or credentials in a public issue. For a sensitive report, request a private contact route without posting its contents.

Material changes to data handling should update this policy and its effective date. This policy applies to this fork; original licensing and upstream attribution remain in the source and LICENSE.
