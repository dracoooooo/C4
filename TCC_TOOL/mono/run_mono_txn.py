import linecache
import os
import sys
from datetime import datetime

from mono.mono_txn_graph import MonosatTxnGraph

sys.setrecursionlimit(1000000)


# Causal checking
def run_monosat_txn_graph_causal(file):
    raw_ops = linecache.getlines(file)
    index = 0
    for i in range(len(raw_ops)):
        if raw_ops[index] == '\n':
            index = i
            break
    if index != 0:
        raw_ops = raw_ops[0:index]
    causal_hist = MonosatTxnGraph(raw_ops)
    wr = causal_hist.get_wr()
    causal_hist.vis_includes(wr)
    causal_hist.vis_is_trans()
    causal_hist.casual_ww()
    # causal_hist.vis_is_trans()
    if causal_hist.check_cycle():
        print('Find Violation!')
        return True


# Read atomic checking
def run_monosat_txn_graph_ra(file):
    raw_ops = linecache.getlines(file)
    index = 0
    for i in range(len(raw_ops)):
        if raw_ops[i] == '\n':
            index = i
            break
    if index != 0:
        raw_ops = raw_ops[0:index]
    causal_hist = MonosatTxnGraph(raw_ops)
    wr = causal_hist.get_wr()
    causal_hist.vis_includes(wr)
    causal_hist.casual_ww()
    if causal_hist.check_cycle():
        print('Find Violation!')
