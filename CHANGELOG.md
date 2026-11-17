# Changelog

All notable changes to this project are documented here.

## 0.1.0 (2026-11-17)

### Features

- **auth:** Add TU Wien SSO login via the default browser
- **lecturetube:** Add LectureTube MCP server
- **tuwel:** Add TUWEL MCP server
- **media:** Transcribe recordings without captions and analyse keyframes
- **media:** Add cloud speech-to-text engine
- **media:** Wait for transcripts instead of polling and notify the user

### Bug fixes

- **ci:** Publish the test report even without a coverage report
- **security:** Fixed security issues (**breaking**)

### Documentation

- Document LectureTube and TUWEL APIs
- Add README
- Describe downloads, releases and license
- **readme:** Updated Readme with animations
- Let the owl breathe gently between waves

### Build

- Set up Gradle build with two server jars
- Take the version from the release build
- Measure code coverage with Kover
- **deps:** Bump the gradle group with 4 updates

### CI

- Test pull requests
- Add manually triggered release workflow
- Publish test and coverage reports after merges to main

### Miscellaneous

- Initial commit
- Add MIT license
