"""Apply the rendered production manifests in dependency order. Requires a live cluster."""

import argparse
import subprocess


# Leave a minute for process overhead beyond kubectl's 600-second rollout wait.
KUBECTL_TIMEOUT_SECONDS = 660

APPLICATIONS = (
    "user-service", "product-service", "order-service", "community-service",
    "review-service", "notification-service", "recommendation-service",
    "virtual-tryon-service", "gateway-service",
)


def deploy(manifest: str) -> None:
    def kubectl(*args: str) -> None:
        subprocess.run(["kubectl", *args], check=True, timeout=KUBECTL_TIMEOUT_SECONDS)

    def apply(selector: str | None = None) -> None:
        args = ["apply", "--server-side", "--field-manager=fitzza-codebuild", "-f", manifest]
        if selector:
            args.extend(["--selector", selector])
        kubectl(*args)

    def wait(resource: str) -> None:
        kubectl("-n", "fitzza", "rollout", "status", resource, "--timeout=600s")

    # Services, configuration, storage and data workloads must exist before app Pods.
    apply("fitzza.io/workload!=application")
    apply("app.kubernetes.io/name in (config-service,discovery-service)")
    for resource in ("deployment/config-service", "deployment/discovery-service",
                     "deployment/redis", "statefulset/qdrant"):
        wait(resource)
    apply()
    for name in APPLICATIONS:
        wait(f"deployment/{name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    deploy(parser.parse_args().manifest)
