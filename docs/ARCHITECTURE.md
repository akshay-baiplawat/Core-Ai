# Core AI - Android Clean Architecture

**Version**: 1.0  
**Last Updated**: 2026-04-29  
**Status**: Design Document

---

## Table of Contents

1. [Overview](#overview)
2. [Clean Architecture Principles](#clean-architecture-principles)
3. [Architecture Layers](#architecture-layers)
4. [Module Structure](#module-structure)
5. [Package Organization](#package-organization)
6. [Dependency Rules](#dependency-rules)
7. [Data Flow](#data-flow)
8. [Key Patterns](#key-patterns)
9. [Technology Stack Integration](#technology-stack-integration)
10. [Examples](#examples)

---

## 1. Overview

Core AI follows **Clean Architecture** principles to ensure:
- **Separation of Concerns**: Clear boundaries between business logic, data access, and UI
- **Testability**: Easy unit testing without UI or database dependencies
- **Maintainability**: Changes in one layer don't ripple through others
- **Scalability**: Easy to add new features without modifying existing code

### Architecture Goals

✅ **Independent of Frameworks**: Business logic doesn't depend on Android SDK or third-party libraries  
✅ **Testable**: Business rules can be tested without UI, database, or external dependencies  
✅ **Independent of UI**: UI can change without affecting business logic (XML → Compose)  
✅ **Independent of Database**: Can swap Room for Realm without touching business logic  
✅ **Independent of External Services**: Business logic doesn't know about external APIs

---

## 2. Clean Architecture Principles

Core AI implements Uncle Bob's Clean Architecture with three concentric circles:

```
┌─────────────────────────────────────────────────────────────┐
│                      Presentation Layer                      │
│  (UI, ViewModels, Composables, Activities, Fragments)       │
│                                                               │
│  ┌─────────────────────────────────────────────────────┐   │
│  │                   Data Layer                         │   │
│  │  (Repositories, Data Sources, API, Database, Cache)  │   │
│  │                                                       │   │
│  │  ┌──────────────────────────────────────────────┐  │   │
│  │  │            Domain Layer                       │  │   │
│  │  │  (Use Cases, Entities, Business Rules)        │  │   │
│  │  │  ⚡ Core business logic (framework-agnostic)  │  │   │
│  │  └──────────────────────────────────────────────┘  │   │
│  │                                                       │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                               │
└─────────────────────────────────────────────────────────────┘

Dependency Direction: Presentation → Data → Domain
```

### The Dependency Rule

**Dependencies point inward**:
- Outer layers can depend on inner layers
- Inner layers **never** depend on outer layers
- Domain layer has **zero** Android dependencies

---

## 3. Architecture Layers

### 3.1 Domain Layer (Innermost Circle)

**Purpose**: Contains business logic, entities, and use cases. Framework-agnostic.

**Components**:
- **Entities**: Core business models (pure Kotlin data classes)
- **Use Cases**: Single-responsibility business operations
- **Repository Interfaces**: Contracts for data access (implementation in Data layer)
- **Domain Exceptions**: Business rule violations

**Key Characteristics**:
- ✅ Pure Kotlin (no Android imports)
- ✅ Zero external dependencies
- ✅ Highly testable (unit tests only)
- ✅ Reusable across platforms (Android, Desktop, Web)

**Example Files**:
```
domain/
├── model/
│   ├── Model.kt                  # Core entity
│   ├── ApiKey.kt
│   ├── InferenceRequest.kt
│   └── InferenceResult.kt
├── repository/
│   ├── ModelRepository.kt        # Interface (implementation in data layer)
│   ├── ApiKeyRepository.kt
│   └── InferenceRepository.kt
├── usecase/
│   ├── DownloadModelUseCase.kt
│   ├── GenerateApiKeyUseCase.kt
│   ├── RunInferenceUseCase.kt
│   └── SwitchModelUseCase.kt
└── exception/
    ├── ModelNotFoundException.kt
    ├── InvalidApiKeyException.kt
    └── InferenceFailedException.kt
```

---

### 3.2 Data Layer (Middle Circle)

**Purpose**: Implements repository interfaces, manages data sources, handles caching.

**Components**:
- **Repository Implementations**: Coordinate between data sources
- **Data Sources**: Local (Room, DataStore) and Remote (Retrofit APIs)
- **DTOs (Data Transfer Objects)**: Network/database models (map to domain entities)
- **Mappers**: Convert DTOs ↔ Domain entities

**Key Characteristics**:
- ✅ Implements domain repository interfaces
- ✅ Knows about Android (Room, DataStore, Context)
- ✅ Handles data persistence and network calls
- ✅ Caching strategies (memory, disk)

**Example Files**:
```
data/
├── repository/
│   ├── ModelRepositoryImpl.kt       # Implements domain/repository/ModelRepository
│   ├── ApiKeyRepositoryImpl.kt
│   └── InferenceRepositoryImpl.kt
├── source/
│   ├── local/
│   │   ├── database/
│   │   │   ├── CoreAIDatabase.kt    # Room database
│   │   │   ├── ModelDao.kt
│   │   │   └── ApiKeyDao.kt
│   │   ├── datastore/
│   │   │   └── PreferencesDataStore.kt
│   │   └── inference/
│   │       └── LiteRTInferenceEngine.kt
│   └── remote/
│       ├── api/
│       │   └── ModelHubApi.kt       # Retrofit interface
│       └── aidl/
│           └── InferenceService.kt  # AIDL service implementation
├── model/
│   ├── ModelEntity.kt               # Room entity
│   ├── ApiKeyEntity.kt
│   └── ModelDto.kt                  # Network response DTO
└── mapper/
    ├── ModelMapper.kt               # Entity ↔ Domain model
    └── ApiKeyMapper.kt
```

---

### 3.3 Presentation Layer (Outer Circle)

**Purpose**: UI components, ViewModels, navigation, state management.

**Components**:
- **Composables**: Jetpack Compose UI components
- **ViewModels**: Hold UI state, call use cases, handle UI events
- **UI State**: Data classes representing screen state
- **Navigation**: Screen routing and arguments
- **Theme**: Design system (colors, typography from DESIGN.md)

**Key Characteristics**:
- ✅ Depends on Domain layer (via ViewModels → Use Cases)
- ✅ Never directly accesses Data layer
- ✅ Android-aware (Context, Lifecycle, Compose)
- ✅ Reactive (StateFlow, MutableState)

**Example Files**:
```
presentation/
├── ui/
│   ├── theme/
│   │   ├── Color.kt                # Anthropic brand colors from DESIGN.md
│   │   ├── Typography.kt
│   │   ├── Theme.kt
│   │   └── Shape.kt
│   ├── component/
│   │   ├── ModelCard.kt            # Reusable components
│   │   ├── ProgressIndicator.kt
│   │   └── ApiKeyItem.kt
│   ├── screen/
│   │   ├── dashboard/
│   │   │   ├── DashboardScreen.kt
│   │   │   ├── DashboardViewModel.kt
│   │   │   └── DashboardState.kt
│   │   ├── modelhub/
│   │   │   ├── ModelHubScreen.kt
│   │   │   ├── ModelHubViewModel.kt
│   │   │   └── ModelHubState.kt
│   │   ├── playground/
│   │   │   ├── PlaygroundScreen.kt
│   │   │   ├── PlaygroundViewModel.kt
│   │   │   └── PlaygroundState.kt
│   │   └── apikeys/
│   │       ├── ApiKeysScreen.kt
│   │       ├── ApiKeysViewModel.kt
│   │       └── ApiKeysState.kt
│   └── navigation/
│       ├── NavGraph.kt
│       └── Screen.kt
└── MainActivity.kt
```

---

## 4. Module Structure

### Current (MVP): Single Module

```
app/
├── src/main/
│   ├── java/com/stridetech/coreai/
│   │   ├── domain/              # Business logic
│   │   ├── data/                # Data access
│   │   ├── presentation/        # UI
│   │   └── di/                  # Hilt modules
│   ├── res/
│   └── AndroidManifest.xml
└── build.gradle.kts
```

**Rationale**: Simpler for MVP, faster iteration.

---

### Future (Post-MVP): Multi-Module

```
CoreAI/
├── app/                         # Main app module (orchestration)
├── feature/
│   ├── modelhub/                # Model download/import UI + logic
│   ├── inference/               # Inference API, AIDL service
│   ├── apikeys/                 # API key management
│   └── playground/              # Testing UI
├── core/
│   ├── domain/                  # Shared business logic
│   ├── data/                    # Shared data access
│   ├── ui/                      # Design system components
│   └── common/                  # Utilities
└── build.gradle.kts
```

**Benefits**:
- **Parallel development**: Teams work on separate features
- **Build speed**: Gradle only rebuilds changed modules
- **Clear boundaries**: Features are isolated
- **Reusability**: Core modules shared across features

---

## 5. Package Organization

### Layered Package Structure (MVP)

```
com.stridetech.coreai/
├── domain/                      # 🟢 Inner circle (no Android deps)
│   ├── model/
│   ├── repository/
│   ├── usecase/
│   └── exception/
├── data/                        # 🟡 Middle circle (Android-aware)
│   ├── repository/
│   ├── source/
│   │   ├── local/
│   │   └── remote/
│   ├── model/
│   └── mapper/
├── presentation/                # 🔴 Outer circle (UI)
│   ├── ui/
│   │   ├── theme/
│   │   ├── component/
│   │   ├── screen/
│   │   └── navigation/
│   └── MainActivity.kt
└── di/                          # Hilt dependency injection
    ├── AppModule.kt
    ├── DataModule.kt
    └── DomainModule.kt
```

---

## 6. Dependency Rules

### ✅ Allowed Dependencies

| Layer | Can Depend On | Cannot Depend On |
|-------|--------------|------------------|
| **Domain** | Nothing | Data, Presentation, Android SDK |
| **Data** | Domain | Presentation |
| **Presentation** | Domain (via Use Cases) | Data (direct access) |

### ❌ Forbidden Dependencies

```kotlin
// ❌ WRONG: ViewModel directly accessing Repository
class DashboardViewModel(
    private val modelRepository: ModelRepositoryImpl  // Data layer!
)

// ✅ CORRECT: ViewModel using Use Case (Domain layer)
class DashboardViewModel(
    private val getModelsUseCase: GetModelsUseCase   // Domain layer
)
```

---

## 7. Data Flow

### Request Flow (User Action → Result)

```
User Interaction
       ↓
Composable (UI)
       ↓
ViewModel (emit event)
       ↓
Use Case (business logic)
       ↓
Repository Interface (domain)
       ↓
Repository Implementation (data)
       ↓
Data Source (Room, Retrofit, LiteRT)
       ↓
Repository maps DTO → Domain Entity
       ↓
Use Case returns Result<Entity>
       ↓
ViewModel updates StateFlow
       ↓
Composable recomposes with new state
```

### Example: Download Model Flow

```kotlin
// 1. User taps "Download" button in UI
@Composable
fun ModelHubScreen(viewModel: ModelHubViewModel) {
    Button(onClick = { viewModel.downloadModel(modelId = "gemma-2b") }) {
        Text("Download")
    }
}

// 2. ViewModel calls Use Case
class ModelHubViewModel(
    private val downloadModelUseCase: DownloadModelUseCase
) : ViewModel() {
    fun downloadModel(modelId: String) {
        viewModelScope.launch {
            downloadModelUseCase(modelId).collect { result ->
                when (result) {
                    is Result.Loading -> _state.value = State.Downloading(result.progress)
                    is Result.Success -> _state.value = State.Downloaded(result.data)
                    is Result.Error -> _state.value = State.Error(result.message)
                }
            }
        }
    }
}

// 3. Use Case executes business logic
class DownloadModelUseCase(
    private val modelRepository: ModelRepository  // Interface from domain
) {
    operator fun invoke(modelId: String): Flow<Result<Model>> {
        return modelRepository.downloadModel(modelId)
    }
}

// 4. Repository implementation handles data
class ModelRepositoryImpl(
    private val remoteDataSource: ModelHubApi,
    private val localDataSource: ModelDao
) : ModelRepository {
    override fun downloadModel(modelId: String): Flow<Result<Model>> = flow {
        emit(Result.Loading(0))
        
        // Download from remote
        val modelDto = remoteDataSource.downloadModel(modelId)
        
        // Save to local database
        val modelEntity = modelMapper.dtoToEntity(modelDto)
        localDataSource.insertModel(modelEntity)
        
        // Map to domain entity
        val model = modelMapper.entityToDomain(modelEntity)
        emit(Result.Success(model))
    }
}
```

---

## 8. Key Patterns

### 8.1 Repository Pattern

**Purpose**: Abstract data access logic from business logic.

```kotlin
// Domain layer: Interface
interface ModelRepository {
    suspend fun getModels(): Result<List<Model>>
    suspend fun downloadModel(modelId: String): Result<Model>
    suspend fun deleteModel(modelId: String): Result<Unit>
}

// Data layer: Implementation
class ModelRepositoryImpl(
    private val remoteApi: ModelHubApi,
    private val localDao: ModelDao
) : ModelRepository {
    override suspend fun getModels(): Result<List<Model>> {
        return try {
            val entities = localDao.getAllModels()
            val models = entities.map { modelMapper.toDomain(it) }
            Result.Success(models)
        } catch (e: Exception) {
            Result.Error(e.message ?: "Unknown error")
        }
    }
}
```

---

### 8.2 Use Case Pattern

**Purpose**: Single-responsibility business operations.

```kotlin
// Each use case does ONE thing
class DownloadModelUseCase(private val repository: ModelRepository)
class GetModelsUseCase(private val repository: ModelRepository)
class DeleteModelUseCase(private val repository: ModelRepository)
class SwitchActiveModelUseCase(private val repository: ModelRepository)

// Usage in ViewModel
class ModelHubViewModel(
    private val downloadModelUseCase: DownloadModelUseCase,
    private val getModelsUseCase: GetModelsUseCase,
    private val deleteModelUseCase: DeleteModelUseCase
) : ViewModel() {
    // Each use case injected separately
}
```

---

### 8.3 MVVM (Model-View-ViewModel)

**Purpose**: Separate UI logic from business logic.

```kotlin
// Model: Domain entity (from domain layer)
data class Model(
    val id: String,
    val name: String,
    val size: Long,
    val isActive: Boolean
)

// View: Composable (observes ViewModel state)
@Composable
fun ModelHubScreen(viewModel: ModelHubViewModel) {
    val state by viewModel.state.collectAsState()
    
    when (state) {
        is ModelHubState.Loading -> LoadingIndicator()
        is ModelHubState.Success -> ModelList(state.models)
        is ModelHubState.Error -> ErrorMessage(state.message)
    }
}

// ViewModel: Holds state, calls use cases
class ModelHubViewModel(
    private val getModelsUseCase: GetModelsUseCase
) : ViewModel() {
    private val _state = MutableStateFlow<ModelHubState>(ModelHubState.Loading)
    val state: StateFlow<ModelHubState> = _state.asStateFlow()
    
    init {
        loadModels()
    }
    
    private fun loadModels() {
        viewModelScope.launch {
            getModelsUseCase().collect { result ->
                _state.value = when (result) {
                    is Result.Success -> ModelHubState.Success(result.data)
                    is Result.Error -> ModelHubState.Error(result.message)
                }
            }
        }
    }
}
```

---

### 8.4 Mapper Pattern

**Purpose**: Convert between layers (DTO ↔ Entity ↔ Domain Model).

```kotlin
// Data layer: Database entity
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "is_active") val isActive: Boolean
)

// Domain layer: Business entity
data class Model(
    val id: String,
    val name: String,
    val size: Long,
    val isActive: Boolean
)

// Mapper: Bidirectional conversion
object ModelMapper {
    fun entityToDomain(entity: ModelEntity): Model {
        return Model(
            id = entity.id,
            name = entity.name,
            size = entity.sizeBytes,
            isActive = entity.isActive
        )
    }
    
    fun domainToEntity(model: Model): ModelEntity {
        return ModelEntity(
            id = model.id,
            name = model.name,
            sizeBytes = model.size,
            isActive = model.isActive
        )
    }
}
```

---

## 9. Technology Stack Integration

### 9.1 Dependency Injection (Hilt)

```kotlin
// di/AppModule.kt
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context
}

// di/DataModule.kt
@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CoreAIDatabase {
        return Room.databaseBuilder(
            context,
            CoreAIDatabase::class.java,
            "core_ai_db"
        ).build()
    }
    
    @Provides
    fun provideModelDao(database: CoreAIDatabase): ModelDao = database.modelDao()
    
    @Provides
    @Singleton
    fun provideModelRepository(
        localDataSource: ModelDao,
        remoteApi: ModelHubApi
    ): ModelRepository {
        return ModelRepositoryImpl(localDataSource, remoteApi)
    }
}

// di/DomainModule.kt
@Module
@InstallIn(ViewModelComponent::class)
object DomainModule {
    @Provides
    fun provideDownloadModelUseCase(
        repository: ModelRepository
    ): DownloadModelUseCase {
        return DownloadModelUseCase(repository)
    }
}
```

---

### 9.2 Room Database

```kotlin
// data/source/local/database/CoreAIDatabase.kt
@Database(
    entities = [ModelEntity::class, ApiKeyEntity::class],
    version = 1,
    exportSchema = false
)
abstract class CoreAIDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun apiKeyDao(): ApiKeyDao
}

// data/source/local/database/ModelDao.kt
@Dao
interface ModelDao {
    @Query("SELECT * FROM models")
    suspend fun getAllModels(): List<ModelEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModel(model: ModelEntity)
    
    @Query("DELETE FROM models WHERE id = :modelId")
    suspend fun deleteModel(modelId: String)
    
    @Query("SELECT * FROM models WHERE is_active = 1 LIMIT 1")
    suspend fun getActiveModel(): ModelEntity?
}
```

---

### 9.3 Jetpack Compose

```kotlin
// presentation/ui/screen/modelhub/ModelHubScreen.kt
@Composable
fun ModelHubScreen(
    viewModel: ModelHubViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    
    Scaffold(
        topBar = { TopAppBar(title = { Text("Model Hub") }) }
    ) { padding ->
        when (state) {
            is ModelHubState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is ModelHubState.Success -> {
                LazyColumn(Modifier.padding(padding)) {
                    items(state.models) { model ->
                        ModelCard(
                            model = model,
                            onDownloadClick = { viewModel.downloadModel(model.id) },
                            onDeleteClick = { viewModel.deleteModel(model.id) }
                        )
                    }
                }
            }
            is ModelHubState.Error -> {
                ErrorScreen(message = state.message)
            }
        }
    }
}
```

---

### 9.4 LiteRT Integration

```kotlin
// data/source/local/inference/LiteRTInferenceEngine.kt
class LiteRTInferenceEngine(
    private val context: Context
) {
    private var interpreter: Interpreter? = null
    
    fun loadModel(modelPath: String) {
        val modelFile = File(context.filesDir, modelPath)
        interpreter = Interpreter(modelFile)
    }
    
    fun runInference(prompt: String): String {
        val inputTensor = preprocessInput(prompt)
        val outputTensor = Array(1) { FloatArray(VOCAB_SIZE) }
        
        interpreter?.run(inputTensor, outputTensor)
        
        return postprocessOutput(outputTensor)
    }
    
    fun unloadModel() {
        interpreter?.close()
        interpreter = null
    }
}
```

---

## 10. Examples

### Complete Feature: Generate API Key

#### 1. Domain Layer

```kotlin
// domain/model/ApiKey.kt
data class ApiKey(
    val id: String,
    val key: String,
    val name: String,
    val createdAt: Long,
    val lastUsed: Long?
)

// domain/repository/ApiKeyRepository.kt
interface ApiKeyRepository {
    suspend fun generateApiKey(name: String): Result<ApiKey>
    suspend fun getAllApiKeys(): Result<List<ApiKey>>
    suspend fun revokeApiKey(id: String): Result<Unit>
}

// domain/usecase/GenerateApiKeyUseCase.kt
class GenerateApiKeyUseCase(
    private val apiKeyRepository: ApiKeyRepository
) {
    suspend operator fun invoke(name: String): Result<ApiKey> {
        if (name.isBlank()) {
            return Result.Error("Name cannot be empty")
        }
        return apiKeyRepository.generateApiKey(name)
    }
}
```

#### 2. Data Layer

```kotlin
// data/model/ApiKeyEntity.kt
@Entity(tableName = "api_keys")
data class ApiKeyEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_used") val lastUsed: Long?
)

// data/source/local/database/ApiKeyDao.kt
@Dao
interface ApiKeyDao {
    @Insert
    suspend fun insertApiKey(apiKey: ApiKeyEntity)
    
    @Query("SELECT * FROM api_keys")
    suspend fun getAllApiKeys(): List<ApiKeyEntity>
    
    @Query("DELETE FROM api_keys WHERE id = :id")
    suspend fun deleteApiKey(id: String)
}

// data/repository/ApiKeyRepositoryImpl.kt
class ApiKeyRepositoryImpl(
    private val apiKeyDao: ApiKeyDao
) : ApiKeyRepository {
    override suspend fun generateApiKey(name: String): Result<ApiKey> {
        return try {
            val id = UUID.randomUUID().toString()
            val key = generateSecureKey()
            val entity = ApiKeyEntity(
                id = id,
                key = key,
                name = name,
                createdAt = System.currentTimeMillis(),
                lastUsed = null
            )
            apiKeyDao.insertApiKey(entity)
            Result.Success(ApiKeyMapper.entityToDomain(entity))
        } catch (e: Exception) {
            Result.Error(e.message ?: "Failed to generate API key")
        }
    }
    
    private fun generateSecureKey(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }
}
```

#### 3. Presentation Layer

```kotlin
// presentation/ui/screen/apikeys/ApiKeysState.kt
sealed class ApiKeysState {
    object Loading : ApiKeysState()
    data class Success(val apiKeys: List<ApiKey>) : ApiKeysState()
    data class Error(val message: String) : ApiKeysState()
}

// presentation/ui/screen/apikeys/ApiKeysViewModel.kt
@HiltViewModel
class ApiKeysViewModel @Inject constructor(
    private val generateApiKeyUseCase: GenerateApiKeyUseCase,
    private val getAllApiKeysUseCase: GetAllApiKeysUseCase
) : ViewModel() {
    
    private val _state = MutableStateFlow<ApiKeysState>(ApiKeysState.Loading)
    val state: StateFlow<ApiKeysState> = _state.asStateFlow()
    
    init {
        loadApiKeys()
    }
    
    fun generateApiKey(name: String) {
        viewModelScope.launch {
            when (val result = generateApiKeyUseCase(name)) {
                is Result.Success -> loadApiKeys()
                is Result.Error -> _state.value = ApiKeysState.Error(result.message)
            }
        }
    }
    
    private fun loadApiKeys() {
        viewModelScope.launch {
            when (val result = getAllApiKeysUseCase()) {
                is Result.Success -> _state.value = ApiKeysState.Success(result.data)
                is Result.Error -> _state.value = ApiKeysState.Error(result.message)
            }
        }
    }
}

// presentation/ui/screen/apikeys/ApiKeysScreen.kt
@Composable
fun ApiKeysScreen(
    viewModel: ApiKeysViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = { TopAppBar(title = { Text("API Keys") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Generate Key")
            }
        }
    ) { padding ->
        when (state) {
            is ApiKeysState.Loading -> LoadingIndicator()
            is ApiKeysState.Success -> {
                LazyColumn(Modifier.padding(padding)) {
                    items((state as ApiKeysState.Success).apiKeys) { apiKey ->
                        ApiKeyItem(apiKey = apiKey)
                    }
                }
            }
            is ApiKeysState.Error -> {
                ErrorMessage(message = (state as ApiKeysState.Error).message)
            }
        }
    }
    
    if (showDialog) {
        GenerateApiKeyDialog(
            onGenerate = { name ->
                viewModel.generateApiKey(name)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}
```

---

## Summary

Core AI's Clean Architecture ensures:

✅ **Testability**: Domain logic tested without Android dependencies  
✅ **Flexibility**: Swap UI (XML→Compose), DB (Room→Realm), or inference engine (LiteRT→MediaPipe) without touching business logic  
✅ **Maintainability**: Clear layer boundaries prevent spaghetti code  
✅ **Scalability**: Easy to add new features (new Use Cases, Repositories, Screens)

**Key Principles to Remember**:
1. Domain layer has **zero Android imports**
2. Dependencies point **inward** (Presentation → Data → Domain)
3. Use Cases are **single-responsibility** operations
4. Repositories **abstract data sources**
5. ViewModels **never** access Data layer directly

---

## References

- **Clean Architecture Book**: Robert C. Martin (Uncle Bob)
- **Android Architecture Guide**: [developer.android.com/topic/architecture](https://developer.android.com/topic/architecture)
- **Now in Android App**: Google's sample app showcasing Clean Architecture
- **PRD**: [PRD-CoreAI.md](./PRD-CoreAI.md)
- **Design System**: [DESIGN.md](../DESIGN.md)

---

**Next Steps**:
1. Set up Hilt dependency injection
2. Create domain layer (entities, use cases, repository interfaces)
3. Implement data layer (Room, repositories)
4. Build presentation layer (Compose UI, ViewModels)
5. Write unit tests for domain layer (80%+ coverage)
