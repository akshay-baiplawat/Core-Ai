# Product Requirements Document: Core AI

**Author**: Product Team  
**Date**: 2026-04-29  
**Status**: Draft  
**Stakeholders**: Engineering, ML Team, Product, Legal, QA

---

## 1. Executive Summary

**Core AI** is an on-device AI inference engine for Android 12+ that enables third-party applications to leverage powerful Large Language Models (LLMs) locally. Core AI solves three critical problems: **privacy** (zero cloud transmission), **efficiency** (shared inference engine eliminates per-app model bundling), and **offline capability** (works without network). The MVP targets Android app developers seeking to add AI features without ML expertise, privacy-conscious end users, and power users who want to experiment with local models.

**Target Launch**: Q3 2026 | **Timeline**: 24 weeks (6 months)

---

## 2. Background & Context

### The Problem Space

**Current State of Mobile AI:**
- Most mobile AI features rely on cloud APIs (OpenAI, Anthropic, Google Gemini), requiring constant network connectivity and sending user data to remote servers
- Privacy-aware users are increasingly concerned about data collection and surveillance
- Cloud-dependent AI fails in offline scenarios: travel, poor connectivity areas, no data plans
- Apps that bundle their own models create massive storage waste: if 10 apps each bundle a 2GB model, that's 20GB of redundant storage

**Market Context:**
- **Cloud AI Dominance**: 95%+ of mobile AI implementations use cloud APIs for ease of integration
- **Emerging On-Device Trend**: Google (Gemini Nano), Apple (on-device ML), and Qualcomm (AI Engine) are pushing on-device inference
- **Storage Constraints**: Average Android users have 64-128GB storage; large models compete with photos, apps, media
- **Privacy Regulations**: GDPR, CCPA, and emerging AI regulations increase liability for cloud data processing

**Competitive Landscape:**

| Solution | Privacy | Offline | Storage Efficiency | Developer Integration |
|----------|---------|---------|-------------------|----------------------|
| **Cloud APIs** (OpenAI, Anthropic) | ❌ Low | ❌ No | ✅ Zero local | ✅ Easy (REST APIs) |
| **Bundled Models** (per-app) | ✅ High | ✅ Yes | ❌ Wasteful | ⚠️ Complex (ML expertise) |
| **Core AI** (this product) | ✅ High | ✅ Yes | ✅ Shared engine | ✅ Easy (local API) |

**What Prompted This:**
1. Developer feedback: "We want AI features but can't justify cloud costs or require users to trust third-party servers"
2. User demand: Growing requests for offline-capable AI tools (note-taking, email drafting, translation)
3. Storage crisis: Users deleting apps due to space constraints from bundled models
4. Regulatory pressure: Companies seeking GDPR/CCPA-compliant AI solutions

---

## 3. Objectives & Success Metrics

### Goals

1. **Launch Stability**: Ship production-ready Android service supporting LiteRT inference with <2s latency for 1B-param models by Q3 2026
2. **Developer Adoption**: Enable 10+ third-party apps to integrate Core AI API within 6 months post-launch
3. **User Growth**: Achieve 50,000+ downloads with 4.0+ Play Store rating
4. **Ecosystem Health**: Generate 500+ active API keys indicating sustained developer engagement

### Non-Goals (Out of Scope for MVP)

1. **iOS version** — Android-only for MVP; iOS requires entirely different tech stack (Core ML vs. LiteRT)
2. **Cloud fallback integration** — Pure on-device philosophy; no hybrid cloud/local architecture
3. **Model training or fine-tuning** — Inference-only; users cannot customize models
4. **Multi-modal support** — Text-only for MVP; no image generation, vision, or audio
5. **Enterprise admin console** — No MDM integration, fleet management, or policy enforcement
6. **Response streaming** — Basic request/response only; SSE/WebSockets deferred to P2

### Success Metrics

| Metric | Current | Target (6 months) | Measurement Method |
|--------|---------|-------------------|-------------------|
| **Downloads** | 0 | 50,000+ | Play Store Console analytics |
| **Third-party integrations** | 0 | 10+ published apps | Developer registry + Play Store search |
| **Avg. inference latency (1B)** | N/A | <2s (P50), <5s (P95) | In-app telemetry (local logs) |
| **Storage per user** | N/A | <5GB (avg 2 models) | Device analytics dashboard |
| **Play Store rating** | N/A | 4.0+ stars | Play Console ratings |
| **Active API keys** | 0 | 500+ | Backend database query |
| **Model switching success rate** | N/A | >95% | Error logs / total switches |
| **Crash-free sessions** | N/A | >99.5% | Firebase Crashlytics |

---

## 4. Target Users & Segments

### Primary: Android App Developers (B2B2C)

**Profile:**
- Indie developers, startup teams (2-10 engineers), mid-size app companies
- Limited ML expertise or budget for ML engineers
- Building productivity, content, communication, or education apps
- Want to differentiate with AI features without managing infrastructure

**Needs:**
- Easy-to-integrate API (REST or AIDL) with clear documentation
- No cloud server costs or API rate limits
- Privacy-first solution to appeal to privacy-conscious users
- Model flexibility: choose between speed (small models) and quality (larger models)

**Pain Points:**
- Cloud APIs add latency, cost, and privacy concerns
- Bundling models requires ML expertise and bloats APK size (100MB+)
- Managing model updates and versioning is complex
- App rejection risks if models violate Play Store policies

**Target Size:** 500+ developers in Year 1

---

### Secondary: Privacy-Conscious End Users (B2C)

**Profile:**
- Security-aware consumers, ages 25-45, tech-savvy
- Professionals handling sensitive data (lawyers, doctors, journalists)
- Users in regulated industries (finance, healthcare)
- Prefer open-source, self-hosted, or on-device solutions over cloud services

**Needs:**
- Absolute certainty that data never leaves device
- Transparency into what models are running and what they're trained on
- Control over which apps can access inference engine
- Offline functionality for travel, remote work, or unreliable connectivity

**Pain Points:**
- Distrust of cloud AI providers after data breaches and privacy scandals
- Frustration with apps that don't work offline
- Lack of visibility into where data goes when using AI features
- Forced acceptance of invasive privacy policies to use AI features

**Target Size:** 10,000-50,000 early adopters in Year 1

---

### Tertiary: Power Users & Tinkerers (B2C)

**Profile:**
- AI enthusiasts, developers, academic researchers
- Early adopters who experiment with cutting-edge tech
- Active in Reddit (r/LocalLLaMA), Hacker News, AI Discord servers
- Willing to sideload models and tweak inference parameters

**Needs:**
- Ability to import custom models (not just curated hub)
- Access to inference parameters (temperature, top-k, max tokens)
- Playground UI to test models before integrating into workflows
- Community sharing: export/import model configurations

**Pain Points:**
- Existing on-device solutions (Termux + llama.cpp) are CLI-only and complex
- No standardized API for building tools on top of local models
- Each experiment requires downloading and managing separate model files
- Lack of performance benchmarking tools for different models

**Target Size:** 1,000-5,000 community advocates in Year 1

---

## 5. User Stories & Requirements

### P0 — Must Have (MVP Blocker)

| # | User Story | Acceptance Criteria |
|---|-----------|-------------------|
| **1** | As a **user**, I want to download optimized LLMs from a curated hub | • Browse list of ≥3 models (e.g., Gemma 2B, Llama 3.2 1B, Phi-3 Mini)<br>• Download with progress indicator (percentage, ETA)<br>• Handle network failures gracefully (retry, pause/resume)<br>• Display model size, description, and requirements |
| **2** | As a **user**, I want to import custom model files from device storage | • Support `.tflite`, `.gguf`, `.bin` formats (based on inference backend)<br>• File picker integration (Android Storage Access Framework)<br>• Validate file integrity before import (checksum, format validation)<br>• Clear error messages for invalid/corrupted files |
| **3** | As a **user**, I want to select which model is active for inference | • View list of downloaded models with metadata (name, size, date added)<br>• One-tap switch between models<br>• Visual indicator of currently active model<br>• Cannot unload model if inference is in progress |
| **4** | As a **user**, I want to delete unused models to free storage | • Confirmation dialog before deletion (prevent accidental loss)<br>• Cannot delete currently active model (must switch first)<br>• Update available storage display after deletion<br>• Option to delete model cache/temp files separately |
| **5** | As a **developer**, I want to generate API keys for my app | • Generate unique API key with one tap<br>• Copy-to-clipboard with success toast<br>• Display key name (app identifier), creation date, last used<br>• Keys are globally unique and cryptographically secure (UUID v4 or better) |
| **6** | As a **developer**, I want to send text prompts and receive completions | • AIDL service OR local HTTP endpoint (localhost:PORT)<br>• JSON request format: `{ "prompt": "...", "api_key": "..." }`<br>• JSON response format: `{ "completion": "...", "latency_ms": 1234 }`<br>• Authentication via API key in request header/body<br>• Error responses: 401 (invalid key), 503 (model not loaded), 500 (inference error) |
| **7** | As a **user**, I want to test the active model in a playground UI | • Simple chat interface (message input + response display)<br>• Send prompt button + loading indicator<br>• Display response with latency timing<br>• Clear conversation history button<br>• Copy response to clipboard option |
| **8** | As a **user**, I want to revoke API keys for misbehaving apps | • View list of active API keys (name, creation date, last used, request count)<br>• One-tap revocation with confirmation dialog<br>• Immediate effect: revoked keys return 401 on next request<br>• Option to regenerate revoked key if user changes mind |

---

### P1 — Should Have (Launch Target)

| # | User Story | Acceptance Criteria |
|---|-----------|-------------------|
| **9** | As a **user**, I want to see memory and battery usage of inference | • System resource dashboard showing:<br>&nbsp;&nbsp;- Current RAM usage (MB, percentage of available)<br>&nbsp;&nbsp;- CPU usage percentage<br>&nbsp;&nbsp;- Battery drain rate (mAh/hour)<br>• Historical usage graphs (last 24 hours, 7 days)<br>• Breakdown by model (if multiple models tested) |
| **10** | As a **developer**, I want detailed error codes for failed requests | • Standardized HTTP-style error codes:<br>&nbsp;&nbsp;- 401: Invalid/expired API key<br>&nbsp;&nbsp;- 429: Rate limit exceeded<br>&nbsp;&nbsp;- 500: Inference engine error<br>&nbsp;&nbsp;- 503: Model not loaded / service unavailable<br>• Descriptive error messages in response body<br>• Debugging suggestions (e.g., "Check model is active in Core AI app") |
| **11** | As a **user**, I want to set inference parameters (temperature, max tokens) | • Settings screen with sliders/text inputs:<br>&nbsp;&nbsp;- Temperature: 0.0-2.0 (default 0.7)<br>&nbsp;&nbsp;- Max tokens: 50-2048 (default 512)<br>&nbsp;&nbsp;- Top-k: 1-100 (default 40)<br>• Defaults optimized for non-technical users<br>• Per-model parameter profiles (save/load presets)<br>• Tooltip explanations for each parameter |
| **12** | As a **user**, I want notifications when model downloads complete | • Push notification on completion (success or failure)<br>• Notification shows model name and final size<br>• Tap notification to open Core AI app<br>• Option to disable download notifications in settings |

---

### P2 — Nice to Have (Future)

| # | User Story | Acceptance Criteria |
|---|-----------|-------------------|
| **13** | As a **developer**, I want streaming responses for long completions | • SSE (Server-Sent Events) or WebSocket support<br>• Token-by-token delivery as generated<br>• Client can cancel mid-stream<br>• Fallback to non-streaming for incompatible clients |
| **14** | As a **user**, I want automatic model updates when new versions release | • Background check for model updates (weekly)<br>• User consent required before downloading update<br>• Display changelog with improvements<br>• Option to keep old version if user prefers |
| **15** | As a **developer**, I want usage analytics for my API key | • Dashboard showing:<br>&nbsp;&nbsp;- Total requests, tokens generated<br>&nbsp;&nbsp;- Response time percentiles (P50, P95, P99)<br>&nbsp;&nbsp;- Error rate by type<br>• Historical trends (daily, weekly, monthly)<br>• Export CSV for analysis |
| **16** | As an **enterprise user**, I want to restrict model access by policy | • Admin console for IT admins<br>• Whitelist/blacklist specific models<br>• Enforce maximum model size limits<br>• Policy enforcement via MDM (Mobile Device Management) integration |

---

## 6. Solution Overview

### High-Level Architecture

```
┌─────────────────────────────────────────────────┐
│              Third-Party Apps                    │
│  (News App, Email Client, Note-Taking, etc.)    │
└─────────────┬───────────────────────────────────┘
              │ API Requests (AIDL/HTTP + API Key)
              ▼
┌─────────────────────────────────────────────────┐
│           Core AI Service Layer                  │
│  • Authentication (API Key Validation)           │
│  • Request Router                                │
│  • Rate Limiting (optional P1)                   │
│  • Error Handling & Logging                      │
└─────────────┬───────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────┐
│        Inference Engine (LiteRT/MediaPipe)       │
│  • Model Loading & Initialization                │
│  • Text Generation (Prompt → Completion)         │
│  • Hardware Acceleration (NPU/GPU Delegates)     │
│  • Memory Management                             │
└─────────────┬───────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────┐
│          Model Management Layer                  │
│  • Download Manager (OkHttp, progress tracking)  │
│  • Local Storage (App Cache + Internal Storage)  │
│  • Model Switching & Lifecycle                   │
│  • Validation & Integrity Checks                 │
└─────────────────────────────────────────────────┘
```

---

### Technology Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| **Language** | Kotlin | Official Android language, concise, null-safe |
| **UI Framework** | Jetpack Compose | Modern declarative UI, reactive state, Material3 support |
| **Design System** | Custom (see [DESIGN.md](../DESIGN.md)) | Anthropic brand colors, typography, components |
| **Architecture** | MVVM + Clean Architecture | Separation of concerns: domain, data, presentation layers |
| **Inference Engine** | **LiteRT** (TensorFlow Lite) or **MediaPipe Generative AI** | LiteRT: mature, broad model support<br>MediaPipe: optimized for Generative AI, newer |
| **Networking** | Retrofit + OkHttp | Industry-standard HTTP client, interceptor support |
| **Dependency Injection** | Hilt | Official Android DI, annotation-based, testable |
| **Database** | Room | Type-safe SQL wrapper for API keys, model metadata |
| **Preferences** | DataStore | Modern replacement for SharedPreferences, async, type-safe |
| **IPC (Inter-Process Communication)** | **AIDL** (primary) + **Ktor** (HTTP fallback) | AIDL: native Android, low latency<br>HTTP: easier integration, cross-platform future |
| **Security** | Android Keystore + EncryptedSharedPreferences | Hardware-backed key storage, AES encryption |
| **Testing** | JUnit 5, Espresso, Compose UI Testing | Unit, integration, and UI test coverage |
| **CI/CD** | GitHub Actions | Automated testing, linting, APK building |

---

### Key Design Decisions

#### 1. **AIDL Primary, Local HTTP Fallback**

**Decision**: Implement both AIDL and HTTP APIs for inference requests.

**Why**:
- **AIDL**: Native Android IPC with lower latency (~10-50ms overhead vs. HTTP's ~100-200ms)
- **HTTP**: Easier for developers unfamiliar with AIDL; future cross-platform expansion (desktop apps, web)

**Tradeoff**:
- AIDL requires more complex client setup (binding to service, managing lifecycle)
- HTTP requires running a local server (port management, security risks if exposed)

**Implementation**:
- AIDL for performance-critical apps (e.g., real-time assistants, autocomplete)
- HTTP for rapid prototyping, web-based tools, or cross-platform clients

---

#### 2. **Single Module (MVP) → Multi-Module (Post-MVP)**

**Decision**: Start with flat single-module structure for MVP; refactor to multi-module after launch.

**Why**:
- **Speed**: Faster iteration for greenfield project; no premature over-engineering
- **Simplicity**: Easier onboarding for new contributors

**Future Refactor**:
```
:app                    (main app + UI shell)
:feature-models         (model management, download, import)
:feature-inference      (inference engine wrapper, AIDL/HTTP services)
:feature-auth           (API key generation, validation, storage)
:core-design-system     (Compose theme, shared components)
:core-data              (Room, DataStore, repositories)
```

**Tradeoff**: Technical debt accumulates faster in single module; requires discipline to avoid tight coupling.

---

#### 3. **Quantized Models Only**

**Decision**: Only support quantized models (INT8, INT4); no full-precision FP32 models.

**Why**:
- **Storage**: Full-precision 7B model = 28GB; INT8 quantized = 7GB; INT4 = 3.5GB
- **Memory**: Mobile devices have 6-12GB RAM; full-precision models exceed available memory
- **Performance**: Quantized models run 2-4x faster on mobile NPUs/GPUs

**Target Model Sizes**:
- Small: 1B params quantized (~500MB-1GB) — fast, lower quality
- Medium: 2-3B params quantized (~1.5GB-2.5GB) — balanced
- Large: 7B params quantized (~4GB-7GB) — highest quality, requires 8GB+ RAM devices

**Tradeoff**: Quality degradation vs. full-precision; acceptable for most mobile use cases.

---

#### 4. **No Cloud Telemetry**

**Decision**: No automatic cloud-based analytics or crash reporting by default.

**Why**:
- **Privacy-first principle**: Core AI's value proposition is zero data transmission
- **Trust**: Sending telemetry undermines user trust and marketing message

**Tradeoff**: Limited visibility into crashes, performance issues, usage patterns.

**Solution**: 
- Optional **local-only logs** stored on device (last 7 days, user-controlled export)
- User opt-in for anonymized crash reports (Firebase Crashlytics) with explicit consent dialog

---

## 7. Open Questions

| Question | Owner | Deadline | Impact |
|----------|-------|----------|--------|
| Which specific models will be in the initial hub? (Gemma 2B, Llama 3.2 1B, Phi-3 Mini, Qwen, etc.) | Product + ML Eng | **Before Sprint 1** | Affects model download infra, licensing, storage planning |
| Do we build our own model hosting CDN or use public repos (HuggingFace, Kaggle, GitHub Releases)? | Engineering + Ops | **Sprint 1** | Affects download architecture, bandwidth costs, reliability |
| AIDL or local HTTP as **primary** API for developer docs? Test both and measure latency/complexity. | Engineering | **Sprint 2** | Determines SDK documentation focus, sample apps |
| What's the minimum device RAM requirement? (Recommend 6GB+ for 2B models) | ML Eng + QA | **Sprint 1** | Affects Play Store compatibility matrix, user expectations |
| How do we handle model compatibility across Android versions? (TFLite GPU delegate varies by OS) | ML Eng | **Sprint 2** | Affects model selection, fallback strategies |
| Should API keys be scoped **per-app** (package name) or **global**? | Product + Security | **Before auth implementation** | Per-app = better security but complex UX; global = simpler but risk of key sharing |
| What's the Play Store content policy compliance strategy for user-generated AI content? | Legal + Product | **Before launch** | Affects TOS, disclaimers, content moderation requirements |
| Do we need a revshare model for developers to monetize via Core AI? (e.g., charge users for API calls) | Business + Product | **Post-MVP** | Could unlock B2B revenue but adds payment infrastructure complexity |

---

## 8. Timeline & Phasing

### Phase 1: Foundation (Sprints 1-3, ~6 weeks)

**Goals:**
- Establish project structure and technical foundation
- Implement design system in Compose
- Prove inference engine works end-to-end

**Tasks:**
- Set up multi-layer architecture: domain, data, presentation packages
- Configure Hilt for dependency injection
- Implement Anthropic design system from [DESIGN.md](../DESIGN.md):
  - Compose theme (colors, typography, spacing)
  - Core components (buttons, cards, inputs)
- Add LiteRT or MediaPipe dependency
- Download single model from hardcoded URL (OkHttp)
- Load `.tflite` model and run basic inference (prompt → completion)
- Basic UI: single screen to trigger test inference

**Milestone**: ✅ Can download and run inference on one hardcoded model locally

**Key Risks**:
- LiteRT setup complexity (GPU delegate issues on some devices)
- Model file size causes OOM (Out of Memory) on low-end devices

---

### Phase 2: Core Features (Sprints 4-7, ~8 weeks)

**Goals:**
- Full model management (download, import, switch, delete)
- Authentication system (API keys)
- Developer-facing API (AIDL or HTTP)

**Tasks:**
- **Model Hub UI**:
  - Browse screen with model cards (name, size, description)
  - Download with progress indicator (OkHttp ProgressListener)
  - Handle pause/resume, retry on failure
- **Model Import**:
  - File picker for `.tflite`, `.gguf`, `.bin`
  - Validate file format and integrity (checksum, magic bytes)
  - Error handling for corrupted/incompatible files
- **Model Switching**:
  - Unload current model, load selected model
  - Handle model switching while inference in progress (queue or reject)
- **API Key Management**:
  - Generate unique keys (UUID v4)
  - Store in Room database with encryption (EncryptedSharedPreferences for sensitive fields)
  - UI: list keys, copy to clipboard, revoke
- **AIDL Service Implementation**:
  - Define AIDL interface: `IInferenceService.aidl`
  - Implement service with API key validation
  - Handle concurrent requests (thread pool, queue)
- **Playground UI**:
  - Chat-style interface (message list, input field)
  - Send prompt to inference engine, display response
  - Show latency metrics

**Milestone**: ✅ Full model management + basic API for third-party apps + playground testing

**Key Risks**:
- AIDL complexity delays testing; consider building HTTP API first for rapid prototyping
- Concurrent inference requests cause crashes; need robust thread management

---

### Phase 3: Polish & Testing (Sprints 8-10, ~6 weeks)

**Goals:**
- Production-grade stability and security
- Comprehensive testing (unit, integration, UI)
- Developer documentation

**Tasks:**
- **Error Handling**:
  - Standardized error codes (401, 500, 503)
  - Graceful degradation (e.g., fallback to CPU if GPU fails)
  - User-friendly error messages
- **Input Validation**:
  - Sanitize API key inputs
  - Validate prompt length (prevent excessively long prompts)
  - Rate limiting (optional P1): max 10 requests/min per API key
- **Performance Optimization**:
  - Reduce inference latency: model caching, GPU delegate tuning
  - Memory management: unload models when app backgrounded
  - Battery optimization: throttle background inference
- **Security Audit**:
  - Penetration testing: attempt API key extraction, MITM attacks
  - Verify API key encryption (keystore usage)
  - Secure IPC: prevent unauthorized binding to AIDL service
- **Testing**:
  - Unit tests: ViewModel logic, repository layer, API key generation (target: 70% coverage)
  - Integration tests: model download, inference pipeline, AIDL service binding
  - UI tests: Compose UI testing for model management, playground
  - Device matrix testing: 5+ devices (Pixel 8, Samsung S24, OnePlus 12, etc.)
- **Developer Documentation**:
  - API reference: AIDL interface methods, HTTP endpoints
  - Integration guide: step-by-step for adding Core AI to an app
  - Sample app: simple note-taking app with AI autocomplete
  - Troubleshooting guide: common errors and fixes

**Milestone**: ✅ Production-ready MVP with 70%+ test coverage, security-audited, documented

**Key Risks**:
- Security vulnerabilities discovered late require architectural changes
- Device-specific bugs (GPU delegates, OOM on low-RAM devices) delay launch

---

### Phase 4: Beta & Launch (Sprints 11-12, ~4 weeks)

**Goals:**
- Validate stability with real users
- Polish Play Store presence
- Build developer community

**Tasks:**
- **Closed Beta**:
  - Recruit 50 beta testers (25 developers, 25 end users)
  - Distribute via Google Play Internal Testing track
  - Collect feedback via surveys, crash reports (opt-in)
  - Fix critical bugs, optimize UX based on feedback
- **Play Store Listing**:
  - Screenshots: model hub, playground, API key management
  - Feature graphic, icon refinement
  - Description highlighting privacy, offline, efficiency benefits
  - Privacy policy and terms of service (legal review)
- **Developer Outreach**:
  - Publish blog post: "Introducing Core AI"
  - Reddit posts: r/androiddev, r/LocalLLaMA
  - YouTube demo video (5-minute walkthrough)
  - Reach out to 10 indie developers for early integrations
- **Launch Checklist**:
  - Play Store review submission (~1-2 weeks approval time)
  - Monitor crash rates, ANRs (Application Not Responding)
  - Respond to user reviews within 24 hours
  - Hotfix process ready for critical bugs

**Milestone**: ✅ Public Play Store launch with 50+ beta testers validated, developer community seeded

**Key Risks**:
- Play Store rejection due to content policy concerns (AI-generated content)
- Low initial downloads if marketing insufficient

---

### Summary Timeline

| Phase | Duration | Key Deliverables | Dependencies |
|-------|----------|-----------------|--------------|
| **Phase 1: Foundation** | 6 weeks | Design system, basic inference working | LiteRT model availability, licensing |
| **Phase 2: Core Features** | 8 weeks | Model management, API, playground | Model hosting solution (CDN vs. public repos) |
| **Phase 3: Polish & Testing** | 6 weeks | Security audit, testing, docs | Access to 5+ test devices |
| **Phase 4: Beta & Launch** | 4 weeks | Beta testing, Play Store launch | Legal review (privacy policy, TOS) |
| **Total** | **24 weeks (6 months)** | Production-ready Core AI on Play Store | Hardware, legal, model licensing |

---

### Phasing Options (If Timeline Compresses)

#### Fast Track (3 months)
- **Cut**: P1 features (resource dashboard, detailed error codes, inference parameters UI)
- **Cut**: HTTP API (AIDL only)
- **Reduce**: Testing scope (manual testing on 2 devices instead of 5)
- **Risk**: Lower quality, more post-launch bugs

#### MVP Lite (2 months)
- **Single model**: No hub, only one pre-selected model
- **AIDL only**: No HTTP fallback
- **Minimal UI**: No playground, only settings
- **Risk**: Poor UX, limited developer appeal

**Recommendation**: Stick to 6-month timeline for polished, production-grade launch. Quality matters more than speed for trust-dependent products like privacy-focused AI.

---

## Appendix: References

- **Design System**: [DESIGN.md](../DESIGN.md) — Anthropic brand colors, typography, components
- **Codebase**: Currently greenfield (only boilerplate); all features to be implemented
- **Competitive Research**:
  - LM Studio (desktop on-device LLMs)
  - Ollama (CLI on-device models)
  - Private LLM iOS app (inspiration for UX)
- **Technical References**:
  - [TensorFlow Lite for Android](https://www.tensorflow.org/lite/android)
  - [MediaPipe Generative AI](https://developers.google.com/mediapipe/solutions/genai)
  - [Android AIDL Guide](https://developer.android.com/guide/components/aidl)

---

## Next Steps

After PRD approval, consider:

1. **Run Pre-Mortem**: Identify risks that could derail the project
2. **Break P0 Stories into Engineering Tasks**: Create Jira/Linear tickets for sprint planning
3. **Draft Developer Integration Guide**: Start SDK documentation outline
4. **Design Play Store Assets**: Screenshots, feature graphic mockups
5. **Model Licensing Review**: Ensure legal compliance for distributing models

**Questions or feedback?** Contact the product team or comment directly in this document.
