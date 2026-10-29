from unittest.mock import Mock

from infra.aws.orchestration.eks_kubernetes_api import EksKubernetesApi, KubernetesApiError


def test_http_error_exposes_status():
    error = KubernetesApiError(404, "missing")
    assert error.status == 404
    assert str(error) == "missing"


def test_read_and_create_use_batch_api_paths(monkeypatch):
    client = object.__new__(EksKubernetesApi)
    client._request = Mock(side_effect=[{"metadata": {"name": "job"}}, {"metadata": {"name": "job"}}])

    client.read_namespaced_job("job", "fitzza")
    client.create_namespaced_job("fitzza", {"kind": "Job"})

    assert client._request.call_args_list[0].args == ("GET", "/apis/batch/v1/namespaces/fitzza/jobs/job")
    assert client._request.call_args_list[1].args == ("POST", "/apis/batch/v1/namespaces/fitzza/jobs", {"kind": "Job"})
