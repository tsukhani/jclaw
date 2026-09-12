"""Worked example of a house-style figure: run from the project directory, writes figures/fig1.pdf."""
import numpy as np, matplotlib.pyplot as plt
from figures import house_style, save, PALETTE
house_style()
N = np.logspace(2, 10, 200); d = 150
fig, ax = plt.subplots(figsize=(6.4, 3.6))
for h, ls, lab in [(1, "-", "1 hop"), (2, "--", "2 hops"), (3, "-.", "3 hops"), (4, ":", "4 hops")]:
    ax.plot(N, np.minimum(1, d * (d - 1) ** (h - 1) / N), linestyle=ls, label=lab)
ax.set_xscale("log"); ax.set_yscale("log")
ax.set_xlabel("Population in each group, N"); ax.set_ylabel("Capped upper bound on pair coverage")
ax.legend(loc="lower left")
print(save(fig, "fig1"))
