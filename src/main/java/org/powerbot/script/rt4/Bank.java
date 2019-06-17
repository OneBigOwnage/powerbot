package org.powerbot.script.rt4;

import java.util.ArrayList;
import java.util.List;

import org.powerbot.script.Condition;
import org.powerbot.script.Filter;
import org.powerbot.script.Locatable;
import org.powerbot.script.MenuCommand;
import org.powerbot.script.Random;
import org.powerbot.script.Tile;

/**
 * Bank
 * A utility class for withdrawing and depositing items, opening and closing the bank, and finding the closest usable bank.
 */
public class Bank extends ItemQuery<Item> {

	public Bank(final ClientContext ctx) {
		super(ctx);
	}

	private static final Filter<Interactive> UNREACHABLE_FILTER = new Filter<Interactive>() {
		@Override
		public boolean accept(final Interactive interactive) {
			if (interactive instanceof Locatable) {
				final Tile tile = ((Locatable) interactive).tile();
				for (final Tile bad : Constants.BANK_UNREACHABLES) {
					if (tile.equals(bad)) {
						return false;
					}
				}
			}
			return true;
		}
	};

	private Interactive getBank() {
		final Player p = ctx.players.local();
        final Tile t = p.tile();

        ctx.npcs.select().name(Constants.BANK_NPCS).viewable().select(UNREACHABLE_FILTER).nearest();
        ctx.objects.select().name(Constants.BANK_BOOTHS, Constants.BANK_CHESTS).viewable().select(UNREACHABLE_FILTER)
                .select(gameObject -> {
                for (String action : gameObject.actions()) {
                    if (action == null) {
                        continue;
                    }

                    if (action.equalsIgnoreCase("Bank")
                            || action.equalsIgnoreCase("Use")
                            || action.equalsIgnoreCase("Open")
                    ) {
                        return true;
                    }
                }

                return false;
            }).nearest();
        if (!ctx.properties.getProperty("bank.antipattern", "").equals("disable")) {
            final Npc npc = ctx.npcs.poll();
            final GameObject object = ctx.objects.poll();
            return t.distanceTo(npc) < t.distanceTo(object) ? npc : object;
        }
        final double dist = Math.min(t.distanceTo(ctx.npcs.peek()), t.distanceTo(ctx.objects.peek()));
        final double d2 = Math.min(2d, Math.max(0d, dist - 1d));
        final List<Interactive> interactives = new ArrayList<Interactive>();
        ctx.npcs.within(dist + Random.nextInt(2, 5)).within(ctx.npcs.peek(), d2);
        ctx.objects.within(dist + Random.nextInt(2, 5)).within(ctx.objects.peek(), d2);
        ctx.npcs.addTo(interactives);
        ctx.objects.addTo(interactives);
        final int len = interactives.size();

        return len == 0 ? ctx.npcs.nil() : interactives.get(Random.nextInt(0, len));
	}

	/**
	 * Returns the absolute nearest bank for walking purposes. Do not use this to open the bank.
	 *
	 * @return the {@link Locatable} of the nearest bank or {@link Tile#NIL}
	 * @see #open()
	 */
	public Locatable nearest() {
		Locatable nearest = ctx.npcs.select().select(UNREACHABLE_FILTER).name(Constants.BANK_NPCS).nearest().poll();

		final Tile loc = ctx.players.local().tile();
		for (final GameObject object : ctx.objects.select().select(UNREACHABLE_FILTER).
				name(Constants.BANK_BOOTHS, Constants.BANK_CHESTS).nearest().limit(1)) {
			if (loc.distanceTo(object) < loc.distanceTo(nearest)) {
				nearest = object;
			}
		}
		if (nearest.tile() != Tile.NIL) {
			return nearest;
		}
		return Tile.NIL;
	}

	/**
	 * Determines if a bank is present in the loaded region.
	 *
	 * @return {@code true} if a bank is present; otherwise {@code false}
	 */
	public boolean present() {
		return nearest() != Tile.NIL;
	}

	/**
	 * @return {@code true} if any bank is in viewport; otherwise {@code false}
	 */
	public boolean inViewport() {
		return getBank().valid();
	}

	/**
	 * Opens a random in-view bank.
	 * Do not continue execution within the current poll after this method so BankPin may activate.
	 *
	 * @return {@code true} if the bank was opened; otherwise {@code false}
	 */
	public boolean open() {
		if (opened()) {
			return true;
		}

		final Interactive interactive = getBank();
		if (!interactive.valid()) {
			return false;
		}

		final Filter<MenuCommand> filter = new Filter<MenuCommand>() {
			@Override
			public boolean accept(final MenuCommand command) {
				final String action = command.action;
				return action.equalsIgnoreCase("Bank") || action.equalsIgnoreCase("Use") || action.equalsIgnoreCase("Open");
			}
		};
		if (interactive.hover()) {
			Condition.wait(new Condition.Check() {
				@Override
				public boolean poll() {
					return ctx.menu.indexOf(filter) != -1;
				}
			}, 100, 3);
		}
		if (interactive.interact(filter)) {
			do {
				Condition.wait(new Condition.Check() {
					@Override
					public boolean poll() {
						return opened();
					}
				}, 150, 15);
			} while (ctx.players.local().inMotion());

			Condition.wait(new Condition.Check() {
				@Override
				public boolean poll() {
					return opened();
				}
			}, 100, 15);
		}
		return opened();

	}

	@Override
	protected List<Item> get() {
		final List<Item> items = new ArrayList<Item>();
		if (!opened()) {
			return items;
		}
		for (final Component c : ctx.widgets.widget(Constants.BANK_WIDGET).component(Constants.BANK_ITEMS).components()) {
			final int id = c.itemId(), stack = c.itemStackSize();
			if (id >= 0 && stack > 0) {
				items.add(new Item(ctx, c, id, stack));
			}
		}
		return items;
	}

	@Override
	public Item nil() {
		return new Item(ctx, null, -1, -1, -1);
	}

	/**
	 * @return {@code true} if the bank is opened; otherwise {@code false}
	 */
	public boolean opened() {
		return ctx.widgets.widget(Constants.BANK_WIDGET).component(Constants.BANK_MASTER).visible();
	}

	/**
	 * @return {@code true} if the bank is not opened, or if it was successfully closed; otherwise {@code false}
	 */
	public boolean close() {
		return !opened() || (ctx.widgets.widget(Constants.BANK_WIDGET).component(Constants.BANK_MASTER).component(Constants.BANK_CLOSE).click(true) && Condition.wait(new Condition.Check() {
			@Override
			public boolean poll() {
				return !opened();
			}
		}, 30, 10));
	}


	/**
	 * Withdraws an item with the provided id and amount.
	 *
	 * @param id     the id of the item
	 * @param amount the amount to withdraw
	 * @return {@code true} if the item was withdrawn, does not determine if amount was matched; otherwise, {@code false}
	 */
	public boolean withdraw(final int id, final Amount amount) {
		return withdraw(id, amount.getValue());
	}

	/**
	 * Withdraws an item with the provided id and amount.
	 *
	 * @param id     the id of the item
	 * @param amount the amount to withdraw
	 * @return {@code true} if the item was withdrawn, does not determine if amount was matched; otherwise, {@code false}
	 */
	public boolean withdraw(final int id, final int amount) {
		return withdraw(select().id(id).poll(), amount);
	}

	/**
	 * Withdraws an item with the provided item and amount.
	 *
	 * @param item   the item instance
	 * @param amount the amount to withdraw
	 * @return {@code true} if the item was withdrawn, does not determine if amount was matched; otherwise, {@code false}
	 */
	public boolean withdraw(final Item item, final int amount) {
		if (!opened() || !item.valid() || amount < -1) {
			return false;
		}

		if (!ctx.widgets.scroll(
				item.component,
				ctx.widgets.widget(Constants.BANK_WIDGET).component(Constants.BANK_ITEMS),
				ctx.widgets.widget(Constants.BANK_WIDGET).component(Constants.BANK_SCROLLBAR),
				true
		)) {
			return false;
		}
		final int count = select().id(item.id()).count(true);
		final String action;
		if (count == 1 || amount == 1) {
			action = "Withdraw-1";
		} else if (amount == 0 || count <= amount) {
			action = "Withdraw-All";
		} else if (amount == 5 || amount == 10) {
			action = "Withdraw-" + amount;
		} else if (amount == -1) {
			action = "Withdraw-All-but-1";
		} else if (amount == -2) {
			action = "Placeholder";
		} else if (amount == -3) {
			action = "Withdraw-" + withdrawXAmount();
		} else if (check(item, amount)) {
			action = "Withdraw-" + amount;
		} else {
			action = "Withdraw-X";
		}
		final int cache = ctx.inventory.select().count(true);
		if (!item.component().visible()) {
			ctx.bank.currentTab(0);
		}
		if (item.contains(ctx.input.getLocation())) {
			if (!(ctx.menu.click(new Filter<MenuCommand>() {
				@Override
				public boolean accept(final MenuCommand command) {
					return command.action.equalsIgnoreCase(action);
				}
			}) || item.interact(action))) {
				return false;
			}
		} else if (!item.interact(action)) {
			return false;
		}
		if (action.endsWith("X")) {
			if (!Condition.wait(new Condition.Check() {
				@Override
				public boolean poll() {
					return ctx.widgets.widget(Constants.CHAT_INPUT).component(Constants.CHAT_INPUT_TEXT).visible();
				}
			})) {
				return false;
			}
			Condition.sleep();
			ctx.input.sendln(amount + "");
		}
		return Condition.wait(new Condition.Check() {
			@Override
			public boolean poll() {
				return cache != ctx.inventory.select().count(true);
			}
		});
	}


	/**
	 * Deposits an item with the provided id and amount.
	 *
	 * @param id     the id of the item
	 * @param amount the amount to deposit
	 * @return {@code true} if the item was deposited, does not determine if amount was matched; otherwise {@code false}
	 */
	public boolean deposit(final int id, final Amount amount) {
		return deposit(id, amount.getValue());
	}

	/**
	 * Deposits an item with the provided id and amount.
	 *
	 * @param id     the id of the item
	 * @param amount the amount to deposit
	 * @return {@code true} if the item was deposited, does not determine if amount was matched; otherwise {@code false}
	 */
	public boolean deposit(final int id, final int amount) {
		if (!opened() || amount < 0) {
			return false;
		}
		final Item item = ctx.inventory.select().id(id).shuffle().poll();
		if (!item.valid()) {
			return false;
		}
		final int count = ctx.inventory.select().id(id).count(true);
		final String action;
		if (count == 1 || amount == 1) {
			action = "Deposit";
		} else if (amount == 0 || count <= amount) {
			action = "Deposit-All";
		} else if (amount == 5 || amount == 10) {
			action = "Deposit-" + amount;
		} else if (check(item, amount)) {
			action = "Deposit-" + amount;
		} else {
			action = "Deposit-X";
		}
		final int cache = ctx.inventory.select().count(true);
		if (item.contains(ctx.input.getLocation())) {
			if (!(ctx.menu.click(new Filter<MenuCommand>() {
				@Override
				public boolean accept(final MenuCommand command) {
					return command.action.equalsIgnoreCase(action);
				}
			}) || item.interact(action))) {
				return false;
			}
		} else if (!item.interact(action)) {
			return false;
		}
		if (action.endsWith("X")) {
			if (!Condition.wait(new Condition.Check() {
				@Override
				public boolean poll() {
					return ctx.widgets.widget(162).component(33).visible();
				}
			})) {
				return false;
			}
			Condition.sleep();
			ctx.input.sendln(amount + "");
		}
		return Condition.wait(new Condition.Check() {
			@Override
			public boolean poll() {
				return cache != ctx.inventory.select().count(true);
			}
		});
	}

	/**
	 * Deposits an item with the provided name and amount.
	 *
	 * @param name   the name of the item
	 * @param amount the amount to deposit
	 * @return {@code true} if the item was deposited, does not determine if amount was matched; otherwise {@code false}
	 */
	public boolean deposit(final String name, final Amount amount) {
		return deposit(name, amount.getValue());
	}

	/**
	 * Deposits an item with the provided name and amount.
	 *
	 * @param name   the name of the item
	 * @param amount the amount to deposit
	 * @return {@code true} if the item was deposited, does not determine if amount was matched; otherwise {@code false}
	 */
	public boolean deposit(final String name, final int amount) {
		return deposit(ctx.inventory.select().name(name).peek().id(), amount);
	}

	/**
	 * Deposits the players inventory excluding the specified ids.
	 *
	 * @param ids the ids of the items to ignore when depositing
	 * @return {@code true} if the items were deposited, determines if amount was matched; otherwise {@code false}
	 */
	public boolean depositAllExcept(final int... ids) {
		return depositAllExcept(new Filter<Item>() {
			@Override
			public boolean accept(final Item item) {
				final int id = item.id();
				for (final int i : ids) {
					if (id == i) {
						return true;
					}
				}
				return false;
			}
		});
	}

	/**
	 * Deposits the players inventory excluding the specified item names.
	 *
	 * @param names the names of the items to ignore when depositing
	 * @return {@code true} if the items were deposited, determines if amount was matched; otherwise {@code false}
	 */
	public boolean depositAllExcept(final String... names) {
		return depositAllExcept(new Filter<Item>() {
			@Override
			public boolean accept(final Item item) {
				for (final String s : names) {
					if (s == null) {
						continue;
					}
					if (item.name().toLowerCase().contains(s.toLowerCase())) {
						return true;
					}
				}
				return false;
			}
		});
	}

	/**
	 * Deposits the players inventory excluding the items that match the provided filter.
	 *
	 * @param filter the filter of the items to ignore when depositing
	 * @return {@code true} if the items were deposited, determines if amount was matched; otherwise {@code false}
	 */
	public boolean depositAllExcept(final Filter<Item> filter) {
		if (ctx.inventory.select().select(filter).count() == 0) {
			return depositInventory();
		}
		for (final Item i : ctx.inventory.select().shuffle()) {
			if (filter.accept(i)) {
				continue;
			}
			deposit(i.id(), Amount.ALL);
		}

		return ctx.inventory.select().count() == ctx.inventory.select(filter).count();
	}

	/**
	 * @return {@code true} if bank has tabs; otherwise {@code false}
	 */

	public boolean tabbed() {
		return ctx.varpbits.varpbit(Constants.BANK_TABS) != Constants.BANK_TABS_HIDDEN;
	}

	/**
	 * @return the index of the current bank tab
	 */
	public int currentTab() {
		return ctx.varpbits.varpbit(Constants.BANK_STATE) / 4;
	}

	/**
	 * Changes the current tab to the provided index.
	 *
	 * @param index the index desired
	 * @return {@code true} if the tab was successfully changed; otherwise {@code false}
	 */
	public boolean currentTab(final int index) {
		final Component c = ctx.widgets.component(Constants.BANK_WIDGET, 21).component(index);
		return (currentTab() == index) || c.click() && Condition.wait(new Condition.Check() {
			@Override
			public boolean poll() {
				return currentTab() == index;
			}
		}, 100, 8);
	}

	/**
	 * Returns the item in the specified tab if it exists.
	 *
	 * @param index the tab index
	 * @return the {@link Item} displayed in the tab; otherwise {@link org.powerbot.script.rt6.Bank#nil()}
	 */
	public Item tabItem(final int index) {
		final Component c = ctx.widgets.component(Constants.BANK_WIDGET, 11).component(10 + index);
		if (c != null && c.valid() && c.itemId() != -1) {
			return new Item(ctx, c);
		}

		return nil();
	}

	/**
	 * @return {@code true} if noted withdrawing mode is selected; otherwise {@code false}
	 */
	public boolean withdrawModeNoted() {
		return ctx.varpbits.varpbit(Constants.BANK_STATE, 0, 0x1) == 1;
	}

	/**
	 * Returns the currently selected withdraw mode.
	 *
	 * @return {@code Amount.UNDEFINED} if no amount is specified. If not, it returns the respective selected withdraw mode quantity.
	 */
	public Amount withdrawModeQuantity() {
		int withdrawModeNumber = ctx.varpbits.varpbit(Constants.BANK_QUANTITY);
		switch(withdrawModeNumber) {
			case Constants.BANK_WITHDRAW_MODE_ONE: return Amount.ONE;
			case Constants.BANK_WITHDRAW_MODE_FIVE: return Amount.FIVE;
			case Constants.BANK_WITHDRAW_MODE_TEN: return Amount.TEN;
			case Constants.BANK_WITHDRAW_MODE_X: return Amount.X;
			case Constants.BANK_WITHDRAW_MODE_ALL: return Amount.ALL;
			default: return Amount.PLACEHOLDER;
		}
	}

	/**
	 * Gives the component value of the quantity amount to be used.
	 *
	 * @param amount specifies the amount to get the component for.
	 * @return {@code -1} if the amount specified doesn't exist. If not, it returns the respective component value.
	 */
	public int quantityComponentValue(Amount amount) {
		int quantityComponentValue;
		switch (amount) {
			case ONE:
				quantityComponentValue = Constants.BANK_QUANTITY_ONE;
				break;
			case FIVE:
				quantityComponentValue = Constants.BANK_QUANTITY_FIVE;
				break;
			case TEN:
				quantityComponentValue = Constants.BANK_QUANTITY_TEN;
				break;
			case X:
				quantityComponentValue = Constants.BANK_QUANTITY_X;
				break;
			case ALL:
				quantityComponentValue = Constants.BANK_QUANTITY_ALL;
				break;
			default:
				quantityComponentValue = -1;
		}
		return quantityComponentValue;
	}
	
	/**
	 * Select or verify the current withdraw quantity mode within the bank. Bank must be opened if you intend to set, but can be checked without opening.
	 *
	 * @param amount the relevant amount enum
	 * @return {@code true} if the passed amount was set, or has been set.
	 */
	public boolean withdrawModeQuantity(Amount amount) {
		int quantityComponentValue;
		if (withdrawModeQuantity() == amount) {
			return true;
		} else if (!opened() || (quantityComponentValue = quantityComponentValue(amount)) < -1) {
			return false;
		} else {
			return (ctx.widgets.widget(Constants.BANK_WIDGET).component(quantityComponentValue).click() && Condition.wait(()-> withdrawModeQuantity() == amount, 30, 10));
		}
	}
	
	/**
	 * Check the current amount that is set to Withdraw-X
	 *
	 * @return The amount representation of withdraw-x
	 */
	public int withdrawXAmount() {
		return ctx.varpbits.varpbit(Constants.BANK_X_VALUE) / 2;
	}

	/**
	 * @param noted {@code true} to set withdrawing mode to noted, {@code false} to set it to withdraw normally
	 * @return {@code true} if withdrawing mode is already set, or was successfully set to the desired withdrawing mode; otherwise {@code false}
	 */
	public boolean withdrawModeNoted(final boolean noted) {
		return withdrawModeNoted() == noted || (ctx.widgets.widget(Constants.BANK_WIDGET).component(noted ? Constants.BANK_NOTE : Constants.BANK_ITEM).interact(noted ? "Note" : "Item") && Condition.wait(new Condition.Check() {
			@Override
			public boolean poll() {
				return withdrawModeNoted() == noted;
			}
		}, 30, 10));
	}

	/**
	 * @return {@code true} if deposit inventory button was clicked successfully; otherwise {@code false}
	 */
	public boolean depositInventory() {
		return ctx.inventory.get().isEmpty() || ctx.widgets.widget(Constants.BANK_WIDGET).component(Constants.BANK_DEPOSIT_INVENTORY).interact("Deposit");
	}

	/**
	 * @return {@code true} if deposit equipment button was clicked successfully; otherwise {@code false}
	 */
	public boolean depositEquipment() {
		return ctx.widgets.widget(Constants.BANK_WIDGET).component(Constants.BANK_DEPOSIT_EQUIPMENT).interact("Deposit");
	}

	private boolean check(final Item item, final int amt) {
		item.hover();
		Condition.wait(new Condition.Check() {
			@Override
			public boolean poll() {
				return ctx.menu.indexOf(new Filter<MenuCommand>() {
					@Override
					public boolean accept(final MenuCommand command) {
						return command.action.startsWith("Withdraw") || command.action.startsWith("Deposit");
					}
				}) != -1;
			}
		}, 20, 10);
		final String s = "-".concat(Integer.toString(amt)) + " ";
		for (final String a : ctx.menu.items()) {
			if (a.contains(s)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Amount
	 * An enumeration providing standard bank amount options.
	 * X is the relative to whatever the current value of X is.
	 */
	public enum Amount {
		X, PLACEHOLDER, ALL_BUT_ONE, ALL, ONE, FIVE(5), TEN(10);

		private final int value;
		
		Amount() {
			value = ordinal() - 3;
		}

		Amount(final int value) {
			this.value = value;
		}

		public int getValue() {
			return value;
		}
	}
}
