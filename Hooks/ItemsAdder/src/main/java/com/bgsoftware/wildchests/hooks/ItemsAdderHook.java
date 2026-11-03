package com.bgsoftware.wildchests.hooks;

import com.bgsoftware.wildchests.WildChestsPlugin;
import com.bgsoftware.wildchests.api.objects.chests.Chest;
import com.bgsoftware.wildchests.api.objects.data.ChestData;
import com.bgsoftware.wildchests.objects.data.WChestData;
import com.bgsoftware.wildchests.scheduler.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Fork addition: lets a chest use an ItemsAdder model.
 * <p>
 * config.yml, inside a chest section:
 * <pre>
 * itemsadder:
 *   model: namespace:item_id   # ItemsAdder item whose model is used
 *   scale: 1.0                 # size of the model on the placed chest
 *   offset-y: 0.0              # vertical offset of the model
 *   rotation: 0                # extra rotation (degrees) added to the chest facing
 *   display-transform: FIXED   # NONE, FIXED, HEAD, GUI, GROUND...
 * </pre>
 * The chest item gets the model of the ItemsAdder item, and a placed chest is covered by an
 * ItemDisplay showing the model. The real chest block stays underneath, so every WildChests
 * feature (hoppers, GUI, breaking, explosions...) keeps working as usual.
 */
@SuppressWarnings("unused")
public final class ItemsAdderHook implements Listener {

    private static WildChestsPlugin plugin;
    private static NamespacedKey modelKey;
    private static Method getInstanceMethod;
    private static Method getItemStackMethod;
    private static final Set<String> warnedModels = new HashSet<>();

    private ItemsAdderHook() {
    }

    public static void register(WildChestsPlugin plugin) {
        ItemsAdderHook.plugin = plugin;
        ItemsAdderHook.modelKey = new NamespacedKey(plugin, "ia_model");

        try {
            Class<?> customStackClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
            getInstanceMethod = customStackClass.getMethod("getInstance", String.class);
            getItemStackMethod = customStackClass.getMethod("getItemStack");
        } catch (Throwable error) {
            WildChestsPlugin.log("&cCouldn't hook into ItemsAdder API: " + error);
            return;
        }

        ItemsAdderHook listener = new ItemsAdderHook();
        Bukkit.getPluginManager().registerEvents(listener, plugin);

        // Re-apply models every time ItemsAdder (re)loads its items (/iareload, startup).
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> loadEventClass = (Class<? extends Event>)
                    Class.forName("dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent");
            Bukkit.getPluginManager().registerEvent(loadEventClass, listener, EventPriority.MONITOR,
                    (l, event) -> {
                        if (loadEventClass.isInstance(event))
                            Scheduler.runTask(ItemsAdderHook::refreshAll, 20L);
                    }, plugin);
        } catch (Throwable error) {
            WildChestsPlugin.log("&cCouldn't listen to ItemsAdder load event: " + error);
        }

        plugin.getProviders().registerChestPlaceListener(chest ->
                Scheduler.runTask(chest.getLocation(), () -> syncModel(chest, null), 1L));
        plugin.getProviders().registerChestBreakListener((offlinePlayer, chest) ->
                Scheduler.runTask(chest.getLocation(), () -> removeModels(chest.getLocation(), null)));
        plugin.getProviders().registerSettingsReloadListener(() -> Scheduler.runTask(ItemsAdderHook::refreshAll, 1L));

        // ItemsAdder may already be loaded.
        Scheduler.runTask(ItemsAdderHook::refreshAll, 40L);

        WildChestsPlugin.log("Hooked into ItemsAdder (custom chest models).");
    }

    /*
     *  Events
     */

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        Chunk chunk = event.getChunk();
        List<Entity> entities = event.getEntities();

        // Remove models whose chest doesn't exist anymore.
        for (Entity entity : entities) {
            Location chestLocation = getModelChestLocation(entity);
            if (chestLocation == null)
                continue;
            Chest chest = plugin.getChestsManager().getChest(chestLocation);
            if (chest == null || getModel(chest.getData()) == null)
                entity.remove();
        }

        // Make sure every chest of the chunk has its model.
        for (Chest chest : plugin.getChestsManager().getChests(chunk))
            syncModel(chest, entities);
    }

    /*
     *  Models logic
     */

    private static void refreshAll() {
        applyItemModels();

        for (Chest chest : plugin.getChestsManager().getChests()) {
            Location location = chest.getLocation();
            World world = location.getWorld();
            if (world == null || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4))
                continue;
            Scheduler.runTask(location, () -> {
                // Respawn so changes in the config (model, scale...) are applied.
                removeModels(location, null);
                syncModel(chest, null);
            });
        }
    }

    private static void applyItemModels() {
        for (ChestData chestData : plugin.getChestsManager().getAllChestData()) {
            String model = getModel(chestData);
            if (model == null)
                continue;

            ItemStack modelItem = getModelItem(model);
            if (modelItem == null)
                continue;

            WChestData wChestData = (WChestData) chestData;
            ItemStack chestItem = wChestData.getItemRaw();
            ItemMeta chestMeta = chestItem.getItemMeta();
            ItemMeta modelMeta = modelItem.getItemMeta();

            if (chestMeta == null || modelMeta == null)
                continue;

            try {
                if (modelMeta.hasItemModel())
                    chestMeta.setItemModel(modelMeta.getItemModel());
            } catch (Throwable ignored) {
            }

            try {
                CustomModelDataComponent component = modelMeta.getCustomModelDataComponent();
                if (!component.getFloats().isEmpty() || !component.getStrings().isEmpty() ||
                        !component.getFlags().isEmpty() || !component.getColors().isEmpty())
                    chestMeta.setCustomModelDataComponent(component);
            } catch (Throwable error) {
                try {
                    //noinspection deprecation
                    if (modelMeta.hasCustomModelData())
                        //noinspection deprecation
                        chestMeta.setCustomModelData(modelMeta.getCustomModelData());
                } catch (Throwable ignored) {
                }
            }

            chestItem.setItemMeta(chestMeta);
            wChestData.setItemRaw(chestItem);
        }
    }

    private static void syncModel(Chest chest, @Nullable List<Entity> chunkEntities) {
        WChestData chestData = (WChestData) chest.getData();
        String model = getModel(chestData);
        Location location = chest.getLocation();

        if (model == null) {
            removeModels(location, chunkEntities);
            return;
        }

        if (hasModel(location, chunkEntities))
            return;

        ItemStack modelItem = getModelItem(model);
        if (modelItem == null)
            return;

        World world = location.getWorld();
        if (world == null)
            return;

        Block block = location.getBlock();
        float yaw = getFacingYaw(block.getBlockData()) + chestData.getCustomModelRotation();

        Location spawnLocation = block.getLocation().add(0.5, 0.5 + chestData.getCustomModelOffsetY(), 0.5);
        spawnLocation.setYaw(yaw);
        spawnLocation.setPitch(0f);

        ItemDisplay.ItemDisplayTransform transform;
        try {
            transform = ItemDisplay.ItemDisplayTransform.valueOf(chestData.getCustomModelTransform().toUpperCase(Locale.ENGLISH));
        } catch (IllegalArgumentException error) {
            transform = ItemDisplay.ItemDisplayTransform.FIXED;
        }

        float scale = (float) chestData.getCustomModelScale();
        String tag = chestData.getName() + ";" + location.getBlockX() + ";" + location.getBlockY() + ";" + location.getBlockZ();
        ItemDisplay.ItemDisplayTransform finalTransform = transform;

        world.spawn(spawnLocation, ItemDisplay.class, (Consumer<ItemDisplay>) display -> {
            display.setItemStack(modelItem);
            display.setItemDisplayTransform(finalTransform);
            display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
            display.setPersistent(true);
            display.getPersistentDataContainer().set(modelKey, PersistentDataType.STRING, tag);
        });
    }

    private static boolean hasModel(Location chestLocation, @Nullable List<Entity> chunkEntities) {
        for (Entity entity : getChunkEntities(chestLocation, chunkEntities)) {
            if (isSameBlock(getModelChestLocation(entity), chestLocation))
                return true;
        }
        return false;
    }

    private static void removeModels(Location chestLocation, @Nullable List<Entity> chunkEntities) {
        for (Entity entity : getChunkEntities(chestLocation, chunkEntities)) {
            if (isSameBlock(getModelChestLocation(entity), chestLocation))
                entity.remove();
        }
    }

    private static List<Entity> getChunkEntities(Location location, @Nullable List<Entity> chunkEntities) {
        if (chunkEntities != null)
            return chunkEntities;
        World world = location.getWorld();
        if (world == null)
            return java.util.Collections.emptyList();
        return Arrays.asList(world.getChunkAt(location.getBlockX() >> 4, location.getBlockZ() >> 4).getEntities());
    }

    @Nullable
    private static Location getModelChestLocation(Entity entity) {
        if (!(entity instanceof ItemDisplay))
            return null;

        PersistentDataContainer container = entity.getPersistentDataContainer();
        String tag = container.get(modelKey, PersistentDataType.STRING);
        if (tag == null)
            return null;

        String[] parts = tag.split(";");
        if (parts.length != 4)
            return null;

        try {
            return new Location(entity.getWorld(), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static boolean isSameBlock(@Nullable Location first, Location second) {
        return first != null && first.getWorld() != null && first.getWorld().equals(second.getWorld()) &&
                first.getBlockX() == second.getBlockX() && first.getBlockY() == second.getBlockY() &&
                first.getBlockZ() == second.getBlockZ();
    }

    private static float getFacingYaw(BlockData blockData) {
        if (!(blockData instanceof Directional))
            return 0f;
        BlockFace facing = ((Directional) blockData).getFacing();
        switch (facing) {
            case NORTH:
                return 180f;
            case EAST:
                return -90f;
            case WEST:
                return 90f;
            default:
                return 0f;
        }
    }

    @Nullable
    private static String getModel(ChestData chestData) {
        if (!(chestData instanceof WChestData))
            return null;
        String model = ((WChestData) chestData).getCustomModel();
        return model == null || model.isEmpty() ? null : model;
    }

    @Nullable
    private static ItemStack getModelItem(String model) {
        try {
            Object customStack = getInstanceMethod.invoke(null, model);
            if (customStack != null)
                return ((ItemStack) getItemStackMethod.invoke(customStack)).clone();
        } catch (Throwable ignored) {
        }

        if (warnedModels.add(model))
            WildChestsPlugin.log("&cItemsAdder item '" + model + "' was not found (is it loaded?).");

        return null;
    }

}
