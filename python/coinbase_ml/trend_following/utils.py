from typing import Callable, TypeVar

import ray
from ray.util import remove_placement_group

A = TypeVar("A")


def run_function_on_each_ray_node(
    f: Callable[[A], None], *args: A, num_cpus_per_actor: int = 1
) -> None:
    num_nodes = len(ray.nodes())
    placement_group = ray.util.placement_group(
        [{"CPU": num_cpus_per_actor}] * num_nodes, strategy="STRICT_SPREAD"
    )
    ray.get(placement_group.ready())
    remote_f = ray.remote(num_cpus=num_cpus_per_actor)(f)
    handles = [
        remote_f.options(placement_group=placement_group).remote(*args)
        for _ in range(num_nodes)
    ]
    ray.wait(handles)
    remove_placement_group(placement_group)
