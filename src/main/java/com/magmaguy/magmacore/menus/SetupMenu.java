package com.magmaguy.magmacore.menus;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import com.magmaguy.magmacore.util.Logger;
import lombok.Generated;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class SetupMenu {
    private static final int nextIcon = 8;
    private static final int infoIcon = 1;
    private static final int removeFilterIcon = 7;
    public static Map<Inventory, SetupMenu> setupMenus = new HashMap<>();
    public static Map<UUID, SetupMenu> activePlayerMenus = new HashMap<>();

    private final int previousIcon = 0;
    private final ArrayList<Integer> validSlots = new ArrayList<>(List.of(
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44));
    private final HashMap<Integer, ContentPackage> contentMap = new HashMap<>();
    private final HashMap<Integer, MenuButton> inventoryMap = new HashMap<>();
    private final Player player;
    private final JavaPlugin ownerPlugin;
    private final MenuButton infoButton;
    private final List<? extends ContentPackage> contentPackages;
    private final List<SetupMenuFilter> filterList;
    private final List<MenuButton> toolbarButtons;
    private final List<Integer> filterSlots = List.of(2, 3, 4, 5, 6);
    private final Map<Integer, List<? extends ContentPackage>> filterMap = new HashMap<>();
    private Inventory inventory;
    private int currentPage = 1;
    private List<? extends ContentPackage> displayedContentPackages = new ArrayList<>();

    public SetupMenu(Player player, MenuButton infoButton, List<? extends ContentPackage> mainContentList, List<SetupMenuFilter> filterList) {
        this(MagmaCore.getInstance().getRequestingPlugin(), player, infoButton, mainContentList, filterList, "Setup menu");
    }

    public SetupMenu(Player player, MenuButton infoButton, List<? extends ContentPackage> mainContentList, List<SetupMenuFilter> filterList, String title) {
        this(MagmaCore.getInstance().getRequestingPlugin(), player, infoButton, mainContentList, filterList, title);
    }

    public SetupMenu(JavaPlugin ownerPlugin, Player player, MenuButton infoButton, List<? extends ContentPackage> mainContentList, List<SetupMenuFilter> filterList) {
        this(ownerPlugin, player, infoButton, mainContentList, filterList, "Setup menu");
    }

    public SetupMenu(JavaPlugin ownerPlugin, Player player, MenuButton infoButton, List<? extends ContentPackage> mainContentList, List<SetupMenuFilter> filterList, String title) {
        this(ownerPlugin, player, infoButton, mainContentList, filterList, List.of(), title);
    }

    public SetupMenu(JavaPlugin ownerPlugin, Player player, MenuButton infoButton, List<? extends ContentPackage> mainContentList, List<SetupMenuFilter> filterList, List<MenuButton> toolbarButtons, String title) {
        this.inventory = Bukkit.createInventory((InventoryHolder) player, 45, title);
        this.player = player;
        this.ownerPlugin = ownerPlugin;
        this.contentPackages = mainContentList;
        this.displayedContentPackages = this.contentPackages;
        this.filterList = filterList;
        this.toolbarButtons = toolbarButtons == null ? List.of() : List.copyOf(toolbarButtons);
        this.infoButton = infoButton;
        this.redrawMenu(1, this.inventory);
        setupMenus.put(this.inventory, this);
        activePlayerMenus.put(player.getUniqueId(), this);
    }

    private void redrawMenu(int page, Inventory inventory) {
        this.currentPage = page;
        setupMenus.remove(inventory);
        this.inventory = inventory;
        inventory.clear();
        this.inventoryMap.clear();
        this.populateNavigationElement();
        this.populateFilterElements();
        this.populateContentPackage();
        if (!inventory.equals(this.player.getOpenInventory().getTopInventory())) {
            this.player.openInventory(inventory);
        }
        setupMenus.put(inventory, this);
        activePlayerMenus.put(player.getUniqueId(), this);
    }

    public static boolean refreshInPlaceFor(Player player) {
        SetupMenu setupMenu = setupMenus.get(player.getOpenInventory().getTopInventory());
        if (setupMenu == null) {
            setupMenu = activePlayerMenus.get(player.getUniqueId());
        }
        if (setupMenu == null || !setupMenu.player.equals(player)) {
            return false;
        }
        setupMenu.redrawMenu(setupMenu.currentPage, setupMenu.inventory);
        return true;
    }

    private void populateNavigationElement() {
        this.inventory.setItem(1, this.infoButton.getItemStack());
        this.inventoryMap.put(1, this.infoButton);
        if (this.currentPage > 1) {
            MenuButton previousButton = new MenuButton(ItemStackGenerator.generateItemStack(Material.ARROW, "&fPrevious page", new ArrayList<>())) {
                @Override
                public void onClick(Player player) {
                    SetupMenu.this.redrawMenu(SetupMenu.this.getCurrentPage() - 1, SetupMenu.this.inventory);
                }
            };
            this.inventoryMap.put(0, previousButton);
            this.inventory.setItem(0, previousButton.getItemStack());
        }
        if ((double) this.currentPage < (double) this.displayedContentPackages.size() / (double) this.validSlots.size()) {
            MenuButton nextButton = new MenuButton(ItemStackGenerator.generateItemStack(Material.ARROW, "&fNext page", new ArrayList<>())) {
                @Override
                public void onClick(Player player) {
                    SetupMenu.this.redrawMenu(SetupMenu.this.getCurrentPage() + 1, SetupMenu.this.inventory);
                }
            };
            this.inventoryMap.put(8, nextButton);
            this.inventory.setItem(8, nextButton.getItemStack());
        }
        if (this.filterList.isEmpty() || this.displayedContentPackages == this.contentPackages) {
            return;
        }
        MenuButton filterResetButton = new MenuButton(Material.BARRIER, "&6Reset filters", new ArrayList<>()) {
            @Override
            public void onClick(Player player) {
                SetupMenu.this.removeFilters();
            }
        };
        this.inventoryMap.put(7, filterResetButton);
        this.inventory.setItem(7, filterResetButton.getItemStack());
    }

    private void populateFilterElements() {
        this.filterMap.clear();
        int counter = 0;
        for (SetupMenuFilter setupMenuFilter : this.filterList) {
            if (counter >= this.filterSlots.size()) break;
            final int finalCounter = counter;
            this.filterMap.put(this.filterSlots.get(counter), setupMenuFilter.contentPackageList);
            MenuButton filterButton = new MenuButton(setupMenuFilter.itemStack) {
                @Override
                public void onClick(Player player) {
                    final int slot = SetupMenu.this.filterSlots.get(finalCounter);
                    SetupMenu.this.displayedContentPackages = SetupMenu.this.filterMap.get(slot);
                    SetupMenu.this.redrawMenu(SetupMenu.this.currentPage, SetupMenu.this.inventory);
                    new BukkitRunnable() {
                        public void run() {
                            if (SetupMenu.this.inventory.getItem(slot) == null) {
                                return;
                            }
                            SetupMenu.this.inventory.getItem(slot).addUnsafeEnchantment(Enchantment.CHANNELING, 1);
                        }
                    }.runTaskLater(SetupMenu.this.ownerPlugin, 1L);
                }
            };
            this.inventory.setItem(this.filterSlots.get(counter), setupMenuFilter.itemStack);
            this.inventoryMap.put(this.filterSlots.get(counter), filterButton);
            ++counter;
        }
        this.populateToolbarElements(counter);
    }

    private void populateToolbarElements(int counter) {
        for (MenuButton toolbarButton : this.toolbarButtons) {
            ItemStack itemStack;
            if (counter >= this.filterSlots.size()) break;
            int slot = this.filterSlots.get(counter);
            if (toolbarButton instanceof ContentPackage contentPackage) {
                itemStack = contentPackage.getItemstack();
            } else {
                itemStack = toolbarButton.getItemStack();
            }
            this.inventory.setItem(slot, itemStack);
            this.inventoryMap.put(slot, toolbarButton);
            ++counter;
        }
    }

    private void populateContentPackage() {
        int counter = 0;
        for (Integer validSlot : this.validSlots) {
            int contentPackageIndex = (this.currentPage - 1) * this.validSlots.size() + counter;
            if (contentPackageIndex >= this.displayedContentPackages.size()) break;
            this.inventory.setItem(validSlot, this.displayedContentPackages.get(contentPackageIndex).getItemstack());
            this.contentMap.put(validSlot, this.displayedContentPackages.get(contentPackageIndex));
            this.inventoryMap.put(validSlot, this.displayedContentPackages.get(contentPackageIndex));
            ++counter;
        }
    }

    private void removeFilters() {
        this.displayedContentPackages = this.contentPackages;
        this.redrawMenu(this.currentPage, this.inventory);
    }

    @Generated
    public int getCurrentPage() {
        return this.currentPage;
    }

    public record SetupMenuFilter(ItemStack itemStack, List<? extends ContentPackage> contentPackageList) {
    }

    public static class SetupMenuListeners implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
        public void onInventoryInteraction(InventoryClickEvent event) {
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }

            SetupMenu setupMenu = setupMenus.get(event.getInventory());
            if (setupMenu == null && event.getView() != null) {
                setupMenu = setupMenus.get(event.getView().getTopInventory());
            }
            if (setupMenu == null) {
                setupMenu = activePlayerMenus.get(player.getUniqueId());
                if (setupMenu != null && event.getView() != null) {
                    String title = event.getView().getTitle();
                    if (!title.contains("Setup menu") && !title.contains("setup")) {
                        setupMenu = null;
                    }
                }
            }

            if (setupMenu == null) {
                return;
            }

            event.setCancelled(true);

            if (event.getClickedInventory() != event.getView().getTopInventory()) {
                return;
            }

            int slot = event.getSlot();
            MenuButton button = setupMenu.inventoryMap.get(slot);
            if (button == null) {
                button = setupMenu.inventoryMap.get(event.getRawSlot());
            }

            if (button == null) {
                return;
            }

            try {
                button.onClick(player);
            } catch (Throwable t) {
                Logger.warn("Error clicking SetupMenu button at slot " + slot + ": " + t.getMessage(), true);
                player.sendMessage(ChatColorConverter.convert("&c[BetterStructures] ボタン操作中にエラーが発生しました: " + t.getMessage()));
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onClose(InventoryCloseEvent event) {
            setupMenus.remove(event.getInventory());
            if (event.getPlayer() instanceof Player player) {
                activePlayerMenus.remove(player.getUniqueId());
            }
        }
    }
}
