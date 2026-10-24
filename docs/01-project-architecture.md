---
description: Defines kubedroid's layered unidirectional-data-flow architecture, package layout, key components, adaptive strategy, security approach, and suggested spec-driven implementation order.
status: proposed
---

# **Project Architecture**

## **Architectural Style**

Layered architecture with **Unidirectional Data Flow** (state flows down, events flow up).

* **UI Layer**: Jetpack Compose screens \+ ViewModels (state holders).
* **Data Layer**: Repositories that own the single source of truth and talk to the Kubernetes client.
* Optional thin Domain layer only if complex orchestration appears later.

All UI state is exposed as StateFlow / immutable UI state objects. Side effects (navigation, toasts, dialogs) are modeled as one-shot events.

## **Package Structure (suggested)**

com.neozeon.kubedroid
├── MainActivity.kt
├── KubeApplication.kt
├── di/                     // Hilt modules
├── navigation/             // Navigation 3 keys, entry providers, back stack helpers
├── ui/
│   ├── theme/
│   ├── components/         // Reusable Compose components (ResourceList, YamlViewer, LogViewer, etc.)
│   ├── adaptive/           // Window size class helpers, Scene strategies
│   ├── cluster/            // Cluster list, add cluster, context switcher
│   ├── resources/          // Resource type list \+ resource instance list
│   ├── detail/             // Resource detail (summary \+ YAML)
│   ├── logs/               // Pod log viewer
│   └── crd/                // CRD discovery and CR instance lists
├── data/
│   ├── repository/         // ClusterRepository, ResourceRepository, LogRepository, CrdRepository
│   ├── model/              // Domain models (ResourceSummary, ClusterConnection, etc.)
│   ├── kubernetes/         // KubernetesClient wrapper, config loading, API facades
│   └── local/              // DataStore, secure credential storage
└── util/                   // YAML helpers, error mapping, extension functions

## **Key Components**

### **Data Layer**

* **KubernetesClientProvider**
  Creates and caches an ApiClient (or Fabric8 KubernetesClient) per cluster context. Handles kubeconfig parsing, token refresh if needed, and TLS settings.
* **ResourceRepository**
  Generic operations: list (with label/field selectors), get, delete, patch/replace, watch (optional for later).
  Specialized methods or subclasses for Pods (logs, restart).
* **CrdRepository**
  List CRDs (apiextensions.k8s.io/v1), then dynamically list instances of a given CR using the dynamic client or generic API.
* **ClusterRepository**
  Manages saved connections, active context, and secure storage of kubeconfigs/tokens.

### **UI Layer**

* **ViewModels** (one per major screen or feature area)
  Expose StateFlow\<UiState\> and accept user events.
  Examples: ResourceListViewModel, ResourceDetailViewModel, PodLogsViewModel, CrdListViewModel.
* **Adaptive navigation with Navigation 3**
  Use NavDisplay \+ user-owned back stack.
  On compact windows: classic single-pane.
  On medium/expanded: list-detail Scene or supporting-pane Scene so the list stays visible while the detail updates.

### **Adaptive Strategy**

* Observe currentWindowAdaptiveInfo().
* Define Navigation 3 Scene strategies:
  * Compact → single destination.
  * Medium/Expanded → list-detail for resource browsing and detail.
* Shared ViewModels (or a parent ViewModel scoped to the navigation graph) so selecting an item in the list updates the detail pane without a full navigation.

## **Data Flow Example (Resource List → Detail)**

1. User selects “Pods”.
2. ResourceListViewModel calls ResourceRepository.listPods().
3. Repository uses the Kubernetes client and emits a Flow\<List\<PodSummary\>\>.
4. ViewModel maps to ResourceListUiState and exposes it.
5. User taps a pod → event is sent to ViewModel (or directly updates a shared selected-item state).
6. Detail pane (or new destination) observes the selected resource and loads full object \+ YAML via the repository.
7. YAML is shown in a read-only editor; “Edit” switches to an editable mode that calls patch or replace on save.

## **Authentication & Security**

* Store kubeconfigs and tokens in the Android Keystore / EncryptedSharedPreferences.
* Never log secrets.
* Support the common kubeconfig auth methods: client certificates, bearer tokens, basic auth, and exec plugins (where practical on Android).
* Clear connection state on logout / remove cluster.

## **Error Handling**

* Map Kubernetes API exceptions (404, 403, 409, etc.) to user-friendly messages.
* Surface RBAC failures clearly (“You do not have permission to list pods in this namespace”).
* Network errors and timeouts are retried with exponential backoff only for idempotent reads.

## **Testing** (for the LLM to follow)

* Unit tests for ViewModels (fake repositories).
* Repository tests against a mocked Kubernetes client or WireMock.
* Compose UI tests for critical flows (list → detail, delete confirmation).
* Prefer pure functions and immutable state so most logic is easy to test without Android instrumentation.

## **Suggested Implementation Order for Spec-Driven Development**

1. Project skeleton \+ Hilt \+ Navigation 3 adaptive shell \+ theme.
2. Cluster connection management (import kubeconfig, store securely, create client).
3. Generic resource list \+ detail with YAML viewer (start with Pods).
4. Pod logs.
5. Edit / delete actions.
6. CRD discovery and CR instance listing.
7. Polish adaptive layouts and error states.
