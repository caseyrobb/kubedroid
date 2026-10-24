
# **Project Overview**

## **Purpose**

kubedroid is a modern, adaptive Android Kubernetes client that lets users inspect and manage cluster resources (core objects and custom resources) from a phone or tablet. The app prioritizes a clear, responsive UI that works well on phones, foldables, and tablets, with direct access to YAML manifests, pod logs, and common lifecycle actions.

## **Goals**

* Provide a reliable, native Android experience for day-to-day cluster inspection and light management.
* Support adaptive layouts that make efficient use of larger screens (list-detail, multi-pane).
* Keep the architecture simple, testable, and aligned with current Android best practices so an LLM can implement and extend it confidently.
* Securely handle cluster credentials (kubeconfig, tokens, certificates).

## **Core Features (MVP)**

* **Cluster connection management**
  Import and manage one or more kubeconfigs (file import, paste, or QR). Support context switching. Store credentials securely.
* **Resource browsing**
  List and search the common core resources:
  Pods, Services, Deployments, ReplicaSets, DaemonSets, StatefulSets, Nodes, Namespaces, ConfigMaps, Secrets (redacted by default), Jobs, CronJobs, PersistentVolumeClaims, etc.
* **Resource detail**
  View summary information and the full YAML manifest (with syntax highlighting).
  Support edit (strategic merge / JSON merge patch or full replace) and delete with confirmation.
* **Pod operations**
  Tail / view logs (with follow, container selector, timestamps).
  Restart pods (delete or scale-to-zero where appropriate).
* **Custom resources**
  Discover CRDs.
  List, search, and view instances of any CR.
  Show YAML for CR instances.
* **Adaptive UI**
  Phone: single-pane navigation.
  Tablet / foldable / large window: list-detail or supporting-pane layouts using Navigation 3 Scenes.

## **Stretch-Goals (for initial version)**

* Full terminal / exec into containers
* Helm management
* Multi-cluster monitoring dashboards or metrics graphs
* Offline mode with full local cache of the cluster
* Role-based UI hiding based on RBAC (we will surface API errors instead)

## **Tech Stack**

* **Language**: Kotlin 2.x
* **UI**: Jetpack Compose \+ Material 3
* **Architecture**: Unidirectional data flow — UI (Composable \+ ViewModel) \+ Data (Repositories)
* **Navigation**: Navigation 3
* **DI**: Hilt
* **Async**: Coroutines \+ Flow / StateFlow
* **Kubernetes client**: Official io.kubernetes:client-java (or Fabric8 if preferred for fluent API)
* **Networking**: OkHttp (used by the Kubernetes client) \+ kotlinx.serialization for any extra needs
* **Local storage**: DataStore (preferences) \+ EncryptedSharedPreferences / Android Keystore for secrets
* **YAML**: SnakeYAML or kotlinx-serialization with a YAML format (or the client’s built-in support)
* **Images / icons**: Coil (if needed) \+ Material icons
* **Target**: Android 17 (API 37), minSdk 26 or higher
* **Build**: Gradle Kotlin DSL, AGP 9.x, KSP

## **High-Level User Flows**

1. Add cluster → select context → land on resource list (default: Pods or a dashboard of key resources).
2. Select a resource type → filtered list → select an item → detail pane (summary \+ YAML tabs).
3. From a Pod detail → open logs (live tail).
4. From any supported resource → edit YAML or delete.
5. Switch to CRDs section → search CRDs → list instances of a CR → view YAML.
