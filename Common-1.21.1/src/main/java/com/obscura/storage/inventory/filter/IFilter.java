package com.obscura.storage.inventory.filter;

import java.util.ArrayList;
import java.util.List;

import com.obscura.storage.inventory.StoredItemStack;
import com.obscura.storage.util.Priority;

public interface IFilter {
	ItemPredicate getItemPred();
	boolean isKeepLast();
	Priority getPriority();

	public static class MultiFilter implements IFilter, ItemPredicate {
		private List<IFilter> filters = new ArrayList<>();
		private boolean cachedKeepLast;
		private Priority cachedPriority;

		public MultiFilter(IFilter... filters) {
			for (IFilter f : filters) {
				if (f instanceof MultiFilter mf) {
					for (IFilter nf : mf.filters) {
						add(nf);
					}
				} else {
					add(f);
				}
			}
			recomputeCache();
		}

		private void add(IFilter nf) {
			if (!filters.contains(nf)) {
				filters.add(nf);
			}
		}

		private void recomputeCache() {
			cachedKeepLast = false;
			for (IFilter f : filters) {
				if (f.isKeepLast()) {
					cachedKeepLast = true;
					break;
				}
			}
			int s = 0;
			for (IFilter f : filters) {
				s += f.getPriority().getSorting();
			}
			cachedPriority = Priority.fromSorting(s);
		}

		@Override
		public ItemPredicate getItemPred() {
			return this;
		}

		/** The combined filters, read-only (Tidy asks whether one of them is an allow-list). */
		public List<IFilter> getFilters() {
			return java.util.Collections.unmodifiableList(filters);
		}

		@Override
		public boolean isKeepLast() {
			return cachedKeepLast;
		}

		@Override
		public Priority getPriority() {
			return cachedPriority;
		}

		@Override
		public boolean test(StoredItemStack stack) {
			for (IFilter f : filters) {
				if(!f.getItemPred().test(stack))
					return false;
			}
			return true;
		}

		@Override
		public void updateState() {
			for (IFilter f : filters) {
				f.getItemPred().updateState();
			}
		}

		/** Sum of the parts. Part versions are fresh values from nextStateVersion, so the sum moves whenever one part changes. */
		@Override
		public long stateVersion() {
			long v = 0;
			for (IFilter f : filters) {
				long p = f.getItemPred().stateVersion();
				if (p == UNVERSIONED)return UNVERSIONED;
				v += p;
			}
			return v;
		}

		@Override
		public String toString() {
			return filters.toString();
		}
	}
}
