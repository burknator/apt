/*-
 * APT - Analysis of Petri Nets and labeled Transition systems
 * Copyright (C) 2012-2014  Members of the project group APT
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package uniol.apt.util;

import java.util.Collection;

/**
 * Some auxiliary functions that make working with
 * plain Java collections slightly less clumsy.
 * <p/>
 * This implementation in Java is inspired by the 
 * Apache Commmons Collections library.
 * 
 * @author Thomas Strathmann
 */
public class CollectionUtils {

	/**
	 * Check if at least one object in a {@link Collection} satisfies
	 * a given {@link Predicate}.
	 *
	 * @param coll the collection
	 * @param p the predicate 
	 * @return true if coll contains an object that satisfies p
	 */
	public static <T> boolean exists(final Collection<T> coll, final Predicate<? super T> p) {
		if(coll != null && p != null) {
			for(final T x : coll) {
				if(p.eval(x))
					return true;
			}
		}
		return false;
	}
	
}
