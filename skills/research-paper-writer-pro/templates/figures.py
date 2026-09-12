"""House-style figures for research-paper-writer-pro.

Usage in a figure script:

    from figures import house_style, save, PALETTE
    import numpy as np, matplotlib.pyplot as plt
    house_style()
    fig, ax = plt.subplots(figsize=(6.4, 3.6))
    ax.plot(x, y, color=PALETTE[0], label="1 hop")            # blue solid
    ax.plot(x, z, color=PALETTE[1], linestyle="--", label="2 hops")   # red dashed
    ax.set_xlabel("Population in each group, N"); ax.set_ylabel("Capped upper bound")
    ax.legend(loc="lower left")
    save(fig, "fig1")                                          # figures/fig1.pdf + .png

Conventions the look depends on: serif text, one colour per series in PALETTE order with the
line styles below, light grey grid, top and right spines removed, panel titles as
"A. Short title", vector PDF output for LaTeX plus a PNG preview.
"""
from __future__ import annotations

import os

import matplotlib as mpl
import matplotlib.pyplot as plt

PALETTE = ["#1f4e79", "#a63d2a", "#9b7a1e", "#7f7f7f"]   # blue, red, gold, grey
LINESTYLES = ["-", "--", "-.", ":"]
SHADES = {"blue": "#dbe7f3", "gold": "#f6efd9", "red": "#f3dcdc"}  # region fills


def house_style() -> None:
    mpl.rcParams.update({
        "font.family": "serif",
        "font.serif": ["Palatino", "TeX Gyre Pagella", "Palatino Linotype", "DejaVu Serif"],
        "mathtext.fontset": "stix",
        "font.size": 9,
        "axes.labelsize": 9.5,
        "axes.titlesize": 10,
        "axes.titleweight": "bold",
        "axes.titlelocation": "left",
        "legend.fontsize": 8,
        "xtick.labelsize": 8,
        "ytick.labelsize": 8,
        "axes.grid": True,
        "grid.color": "#d9d9d9",
        "grid.linewidth": 0.6,
        "axes.spines.top": False,
        "axes.spines.right": False,
        "axes.prop_cycle": mpl.cycler(color=PALETTE, linestyle=LINESTYLES),
        "lines.linewidth": 1.6,
        "legend.frameon": True,
        "legend.framealpha": 1.0,
        "legend.edgecolor": "#bbbbbb",
        "figure.dpi": 110,
        "savefig.bbox": "tight",
        "pdf.fonttype": 42,
    })


def save(fig: plt.Figure, name: str, directory: str = "figures") -> str:
    """Write figures/<name>.pdf for LaTeX and figures/<name>.png for previews; return the PDF path."""
    os.makedirs(directory, exist_ok=True)
    pdf = os.path.join(directory, f"{name}.pdf")
    fig.savefig(pdf)
    fig.savefig(os.path.join(directory, f"{name}.png"), dpi=200)
    plt.close(fig)
    return pdf


def panel_title(ax: plt.Axes, letter: str, title: str) -> None:
    """Two-panel figures label panels 'A. Title' / 'B. Title' above each axes."""
    ax.set_title(f"{letter}. {title}")
