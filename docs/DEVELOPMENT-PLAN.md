# CoreAI Android Development Plan

**Project:** CoreAI - On-Device AI Inference Engine for Android  
**Date:** 2026-04-29  
**Branch:** Development  
**Status:** Ready for Implementation

---

## Context

CoreAI is an on-device AI inference engine for Android 12+ that enables third-party applications to leverage Large Language Models (LLMs) locally. The project currently exists as a greenfield Android app shell with comprehensive documentation (PRD, ARCHITECTURE, DESIGN.md) but **zero implementation**. Only a single empty MainActivity.kt exists. All domain, data, and presentation layers need to be built from scratch.

**Why this matters:**
- **Privacy-first**: Zero cloud transmission - all inference runs locally
- **Storage efficiency**: Shared inference engine eliminates per-app model bundling
- **Offline capability**: Works without network connectivity
- **Developer-friendly**: Simple API for third-party apps to integrate AI features

**Current Progress:** ~5% (only Android shell with basic gradle setup)  
**Target:** MVP launch Q3 2026 (24 weeks from now)

---

## Strategic Approach

We'll implement using a **phased rollout** aligned with the PRD's 4-phase timeline:

### Phase 1: Foundation (Sprints 1-3, ~6 weeks)
**Goal:** Prove end-to-end inference works with a single hardcoded model

**Deliverables:**
- Clean Architecture layers (domain/data/presentation packages)
- Jetpack Compose theme implementing Cal.com design system
- Hilt dependency injection configured
- LiteRT or MediaPipe integration
- Basic model download + inference execution
- Single test UI screen

### Phase 2: Core Features (Sprints 4-7, ~8 weeks)
**Goal:** Full model management + developer API + playground

**Deliverables:**
- Model Hub UI (browse, download, import, delete)
- API Key management (generate, store, revoke)
- AIDL service for inference API
- Playground UI for testing
- Room database for persistence

### Phase 3: Polish & Testing (Sprints 8-10, ~6 weeks)
**Goal:** Production-grade stability, security, comprehensive testing

**Deliverables:**
- Error handling & validation
- Security audit (keystore, API key encryption)
- 80%+ test coverage (unit, integration, UI tests)
- Performance optimization
- Developer documentation

### Phase 4: Beta & Launch (Sprints 11-12, ~4 weeks)
**Goal:** Public launch with validated stability

**Deliverables:**
- Closed beta with 50 testers
- Play Store listing and assets
- Developer outreach (blog, community)
- Launch monitoring & hotfix readiness

---

## Implementation Plan

### Phase 1: Foundation (Current Priority)

#### Step 1: Dependency Setup (Week 1)

**Files to modify:**
- [gradle/libs.versions.toml](../gradle/libs.versions.toml)
- [app/build.gradle.kts](../app/build.gradle.kts)

**Add dependencies:** Compose, Hilt, Room, DataStore, Retrofit, OkHttp, Coroutines, TensorFlow Lite, Security

See full dependency list in the plan for all version numbers and library declarations.

#### Step 2: Architecture Package Creation (Week 1)

Create clean architecture package structure:
```
com.stridetech.coreai/
├── domain/           # Business logic (framework-agnostic)
├── data/             # Data access layer
├── presentation/     # UI layer
└── di/               # Hilt dependency injection modules
```

#### Step 3-7: Implementation Steps

- **Week 1-2:** Implement Compose Design System (Color, Typography, Shape, Theme)
- **Week 2:** Domain Layer (entities, repository interfaces, use cases)
- **Week 2-3:** Data Layer (Room database, LiteRT engine, repositories)
- **Week 3:** Hilt Dependency Injection setup
- **Week 3-4:** Test UI Screen with inference capability

---

### Phase 2: Core Features (After Phase 1 Complete)

**Priority order:**
1. Model Hub UI
2. Model Import
3. Model Switching
4. API Key Management
5. AIDL Service
6. Playground UI
7. HTTP Server (optional)

---

### Phase 3: Polish & Testing (After Phase 2 Complete)

**Testing strategy:**
- Unit tests (80%+ coverage target)
- Integration tests (Room, LiteRT engine)
- UI tests (Compose UI Testing)
- Security audit
- Performance optimization

---

### Phase 4: Beta & Launch (After Phase 3 Complete)

- Closed beta (50 testers)
- Play Store preparation
- Developer outreach
- Launch monitoring

---

## Verification & Testing

### End-to-End Verification (Phase 1 Complete)

**Test on physical Android 12+ device:**
1. Build: `./gradlew installDebug`
2. Enter prompt: "Explain quantum computing in one sentence"
3. Run inference
4. Verify result displays with latency
5. Test error handling

### Automated Testing

```bash
./gradlew test                    # Unit tests
./gradlew connectedAndroidTest   # Integration + UI tests
./gradlew jacocoTestReport       # Coverage report
```

### Device Testing Matrix

- **High-end**: Pixel 8 Pro (12GB RAM)
- **Mid-range**: Samsung Galaxy S24 (8GB RAM)
- **Budget**: OnePlus 12 (6GB RAM)

---

## Risk Mitigation

### Critical Risks

1. **LiteRT GPU Delegate Incompatibility** - Mitigation: CPU fallback, test on multiple devices
2. **Large Model OOM on Low-RAM Devices** - Mitigation: RAM warnings, quantized models only
3. **AIDL Service Complexity** - Mitigation: HTTP API fallback, prototype early
4. **Play Store Rejection** - Mitigation: Early policy review, content disclaimers
5. **Model Licensing Uncertainty** - Mitigation: Research licenses early, prefer permissive

---

## Success Criteria

### Phase 1 Success
✅ Can download and run inference on one hardcoded model
- App builds without crashes
- Inference completes in <5s
- Clean Architecture implemented
- Hilt DI configured

### MVP Success (All Phases)
✅ All P0 user stories implemented
✅ 80%+ test coverage
✅ Security audit passed
✅ Launch metrics: 50K+ downloads, 4.0+ rating, <2s latency

---

## Open Questions

**High Priority (need answers before Phase 1):**

1. **Which models in initial hub?** Gemma 2B + Llama 3.2 1B + Phi-3 Mini vs. single model
2. **Model hosting?** Own CDN vs. HuggingFace vs. GitHub Releases
3. **AIDL or HTTP primary API?** Lower latency vs. easier integration
4. **Min RAM requirement?** 4GB vs. 6GB vs. 8GB+ devices
5. **API key scoping?** Per-app vs. global

---

## References

- **PRD:** [PRD-CoreAI.md](./PRD-CoreAI.md)
- **Architecture:** [ARCHITECTURE.md](./ARCHITECTURE.md)
- **Design:** [../DESIGN.md](../DESIGN.md)
- **Android Architecture:** https://developer.android.com/topic/architecture
- **TensorFlow Lite:** https://www.tensorflow.org/lite/android
- **MediaPipe:** https://developers.google.com/mediapipe/solutions/genai

---

## Next Steps

**Day 1:**
1. Verify build: `./gradlew clean build`
2. Add dependencies to gradle/libs.versions.toml
3. Update app/build.gradle.kts
4. Create package structure

**Week 1:**
1. Implement Compose theme (Color, Typography, Shape, Theme)
2. Create domain entities
3. Set up Hilt DI modules
4. Configure Room database

**Week 2-3:**
1. Implement data layer
2. Integrate LiteRT
3. Build test UI
4. Run first inference

---

**Plan Status:** ✅ Ready for Execution  
**Duration:** 24 weeks (6 months)  
**Current Phase:** Phase 1, Week 1  
**Next Milestone:** End-to-end inference (6 weeks)
