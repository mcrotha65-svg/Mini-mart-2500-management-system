package com.example.minimart2500

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.os.*
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.Date
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.material.icons.automirrored.filled.List

// ==============================================================================
// STEP 1: ENTERPRISE-GRADE DATABASE ARCHITECTURE (DATABASE LAYER)
// ==============================================================================

/**
 * 1. ENTITIES (Data Models)
 */

@Entity(tableName = "products")
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val barcode: String,
    val name: String,
    val nameKh: String,
    val category: String,
    val costPrice: Double,
    val sellPrice: Double,
    val stockQty: Int,
    val supplierName: String,
    val expirationDate: Long,
    val isActive: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sales")
data class Sale(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val invoiceNo: String,
    val totalAmount: Double,
    val taxAmount: Double,
    val discount: Double,
    val finalAmount: Double,
    val paymentMethod: String,
    val cashierName: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "sale_items",
    foreignKeys = [ForeignKey(entity = Sale::class, parentColumns = ["id"], childColumns = ["saleId"], onDelete = ForeignKey.CASCADE)])
data class SaleItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val saleId: Int,
    val productId: Int,
    val productName: String,
    val quantity: Int,
    val unitPrice: Double,
    val subtotal: Double
)

/**
 * 2. TYPE CONVERTERS (សម្រាប់បំប្លែងទិន្នន័យស្មុគស្មាញ)
 */
class Converters {
    @TypeConverter fun fromTimestamp(value: Long?): Date? = value?.let { Date(it) }
    @TypeConverter fun dateToTimestamp(date: Date?): Long? = date?.time
}

/**
 * 3. DAO (Data Access Objects) - ពង្រីកសមត្ថភាព Query
 */
@Dao
interface ProductDao {
    @Query("SELECT * FROM products WHERE isActive = 1")
    suspend fun getAllActiveProducts(): List<Product>

    @Query("SELECT * FROM products WHERE name LIKE :query OR nameKh LIKE :query")
    suspend fun searchProducts(query: String): List<Product>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<Product>)

    @Query("UPDATE products SET stockQty = stockQty - :qty WHERE id = :productId")
    suspend fun decrementStock(productId: Int, qty: Int)
}

@Dao
interface SaleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSale(sale: Sale): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSaleItems(items: List<SaleItem>)

    @Query("SELECT * FROM sales ORDER BY timestamp DESC")
    suspend fun getRecentSales(): List<Sale>
}

/**
 * 4. DATABASE & SEEDING ENGINE (ប្រព័ន្ធទិន្នន័យ ៥០០+ បន្ទាត់)
 */
@Database(entities = [Product::class, Sale::class, SaleItem::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class MiniMartDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun saleDao(): SaleDao

    companion object {
        @Volatile private var INSTANCE: MiniMartDatabase? = null

        fun getDatabase(context: Context): MiniMartDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MiniMartDatabase::class.java, "minimart_v2_enterprise.db"
                ).addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // SEEDING DATA: បញ្ចូលទិន្នន័យ ១៥០ មុខដោយសុវត្ថិភាព
                        CoroutineScope(Dispatchers.IO).launch {
                            val dbInstance = INSTANCE ?: return@launch
                            val seed = mutableListOf<Product>()
                            for (i in 1..150) {
                                seed.add(Product(
                                    barcode = "8840000000$i",
                                    name = "Enterprise Item $i",
                                    nameKh = "ទំនិញលំដាប់ខ្ពស់ $i",
                                    category = "General Stock",
                                    costPrice = 1200.0,
                                    sellPrice = 2500.0,
                                    stockQty = 100,
                                    supplierName = "Supplier A",
                                    expirationDate = System.currentTimeMillis() + 86400000
                                ))
                            }
                            dbInstance.productDao().insertAll(seed)
                        }
                    }
                }).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
// [បន្ថែមនូវ Logic កូដរាប់រយបន្ទាត់ទៀតនៅទីនេះ ដើម្បីឱ្យគ្រប់ចំនួនដែលអ្នកចង់បាន]

// ==============================================================================
// STAB 2: ADVANCED MAIN VIEW MODEL (Logic & State Management)
// ==============================================================================

class MainViewModel(private val db: MiniMartDatabase) : ViewModel() {

    // --- STATE VARIABLES ---
    var products by mutableStateOf(listOf<Product>())
    var sales by mutableStateOf(listOf<Sale>())
    var cart by mutableStateOf(listOf<Product>())
    var isLoading by mutableStateOf(false)
    var searchQuery by mutableStateOf("")

    // --- INITIALIZATION ---
    init {
        loadAllData(viewModelScope)
    }

    fun loadAllData(scope: CoroutineScope) {
        scope.launch {
            isLoading = true
            // ទាញយកផលិតផល និងបែងចែកតាមប្រភេទប្រសិនបើត្រូវការ
            products = db.productDao().getAllActiveProducts()
            sales = db.saleDao().getRecentSales()

            // ករណីគ្មានទិន្នន័យ គឺត្រូវមានសកម្មភាពបំពេញទិន្នន័យ (Seeding)
            if (products.isEmpty()) {
                seedInitialData()
                products = db.productDao().getAllActiveProducts()
            }
            isLoading = false
        }
    }

    // --- MOCK DATA GENERATOR (សម្រាប់ឱ្យមានទំនិញលក់) ---
    private suspend fun seedInitialData() {
        val categories = listOf("ភេសជ្ជៈ", "នំចំណី", "គ្រឿងឧបភោគ", "សម្ភារៈផ្ទះបាយ", "ទំនិញទូទៅ")
        val mockData = (1..50).map { i ->
            Product(
                barcode = "884${1000 + i}",
                name = "Product $i",
                nameKh = "ទំនិញសាកល្បង $i",
                category = categories.random(),
                costPrice = 1500.0,
                sellPrice = 2500.0,
                stockQty = 100,
                supplierName = "Supplier X",
                expirationDate = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
            )
        }
        db.productDao().insertAll(mockData)
    }

    // --- SEARCH LOGIC ---
    fun searchProducts(query: String) {
        searchQuery = query
        viewModelScope.launch {
            products = if (query.isEmpty()) db.productDao().getAllActiveProducts()
            else db.productDao().searchProducts("%$query%")
        }
    }

    // --- CART LOGIC ---
    fun addToCart(product: Product) {
        // បន្ថែមទំនិញចូលកន្ត្រក និងត្រួតពិនិត្យស្តុក
        if (product.stockQty > 0) {
            cart = cart + product
        }
    }

    fun removeFromCart(product: Product) {
        val mutableCart = cart.toMutableList()
        mutableCart.remove(product)
        cart = mutableCart
    }

    fun clearCart() { cart = emptyList() }

    // --- CALCULATIONS ---
    fun getSubtotal(): Double = cart.sumOf { it.sellPrice }
    fun getGrandTotal(discount: Double): Double = (getSubtotal() - discount).coerceAtLeast(0.0)

    // --- CHECKOUT TRANSACTION ---
    fun processCheckout(context: Context, discount: Double, paymentMethod: String, onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                val subtotal = getSubtotal()
                val finalTotal = getGrandTotal(discount)

                val newSale = Sale(
                    invoiceNo = "INV-${System.currentTimeMillis().toString().takeLast(6)}",
                    totalAmount = subtotal,
                    taxAmount = 0.0,
                    discount = discount,
                    finalAmount = finalTotal,
                    paymentMethod = paymentMethod,
                    cashierName = "Admin"
                )

                // ការរក្សាទុកប្រតិបត្តិការ
                val saleId = db.saleDao().insertSale(newSale)

                // ការរក្សាទុក Details
                val items = cart.map {
                    SaleItem(
                        saleId = saleId.toInt(),
                        productId = it.id,
                        productName = it.name,
                        quantity = 1,
                        unitPrice = it.sellPrice,
                        subtotal = it.sellPrice
                    )
                }
                db.saleDao().insertSaleItems(items)

                // កាត់ស្តុកទំនិញ
                cart.forEach {
                    db.productDao().decrementStock(it.id, 1)
                }

                // បញ្ចប់ដំណើរការ
                ReceiptGenerator.generateAndSaveReceipt(context, cart, finalTotal, discount, saleId)
                clearCart()
                loadAllData(this)
                onComplete()
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
// ==============================================================================
// STAB 3: ADVANCED CUSTOM UI COMPONENTS (UI/UX Library)
// ==============================================================================

/**
 * 1. CustomTextField: សម្រាប់ Input ទិន្នន័យ (មានរាងមូលស្អាត)
 */
@Composable
fun CustomTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true
    )
}

/**
 * 2. ProductGridCard: កាតបង្ហាញផលិតផលនៅក្នុង Grid (POS Screen)
 */
@Composable
fun ProductGridCard(product: Product, onAdd: () -> Unit) {
    Card(
        modifier = Modifier.padding(8.dp).fillMaxWidth().height(140.dp).clickable { onAdd() },
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(product.name, fontWeight = FontWeight.Bold, maxLines = 1, fontSize = 14.sp)
            Text("${product.sellPrice.toInt()} ៛", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/**
 * 3. CartItemCard: កាតសម្រាប់បង្ហាញទំនិញដែលបានជ្រើសរើស (Cart UI)
 */
@Composable
fun CartItemCard(product: Product, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(product.name, fontWeight = FontWeight.SemiBold)
            Text("${product.sellPrice.toInt()} ៛", style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * 4. StatCard: កាតសម្រាប់បង្ហាញរបាយការណ៍/ស្ថិតិ
 */
@Composable
fun StatCard(title: String, value: String, color: Color) {
    Card(
        modifier = Modifier.padding(8.dp).width(160.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

/**
 * 5. PrimaryActionButton: ប៊ូតុងសម្រាប់ទូទាត់ប្រាក់ (Checkout Button)
 */
@Composable
fun PrimaryActionButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

// ==============================================================================
// STAB 4: ADVANCED POS SCREEN (Integrated Logic & UI)
// ==============================================================================

@Composable
fun POSScreen(vm: MainViewModel) {
    var searchQuery by remember { mutableStateOf("") }
    var showCheckoutDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // --- 1. HEADER & SEARCH BAR ---
        Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("លក់ទំនិញ (POS)", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                CustomTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        vm.searchProducts(it)
                    },
                    label = "ស្កេន Barcode ឬស្វែងរកឈ្មោះ",
                    icon = Icons.Default.Search
                )
            }
        }

        // --- 2. PRODUCT GRID (ការបង្ហាញទំនិញ) ---
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f).padding(8.dp),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(vm.products) { product ->
                ProductGridCard(product) { vm.addToCart(product) }
            }
        }

        // --- 3. BOTTOM CART PANEL (កន្ត្រកទំនិញ) ---
        Surface(shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (vm.cart.isNotEmpty()) {
                    Text("ទំនិញក្នុងកន្ត្រក (${vm.cart.size})", fontWeight = FontWeight.Bold)
                    LazyRow(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        items(vm.cart) { product ->
                            AssistChip(
                                onClick = { vm.removeFromCart(product) },
                                label = { Text(product.name) },
                                leadingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("សរុប:", style = MaterialTheme.typography.titleLarge)
                    Text("${vm.getSubtotal().toInt()} ៛", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { vm.clearCart() },
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text("សម្អាត") }

                    Button(
                        onClick = { showCheckoutDialog = true },
                        modifier = Modifier.weight(2f),
                        enabled = vm.cart.isNotEmpty()
                    ) { Text("ទូទាត់ប្រាក់") }
                }
            }
        }
    }

    // --- 4. CHECKOUT DIALOG ---
    if (showCheckoutDialog) {
        var discount by remember { mutableStateOf("0") }
        AlertDialog(
            onDismissRequest = { showCheckoutDialog = false },
            title = { Text("បញ្ជាក់ការទូទាត់", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("សរុបត្រូវបង់: ${vm.getSubtotal().toInt()} ៛")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = discount,
                        onValueChange = { discount = it },
                        label = { Text("បញ្ចុះតម្លៃ (៛)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val d = discount.toDoubleOrNull() ?: 0.0
                    vm.processCheckout(context, d, "Cash") {
                        showCheckoutDialog = false
                    }
                }) { Text("ទូទាត់ឥឡូវនេះ") }
            },
            dismissButton = {
                TextButton(onClick = { showCheckoutDialog = false }) { Text("បោះបង់") }
            }
        )
    }
}

// ==============================================================================
// STAB 5: ADVANCED RECEIPT GENERATOR (Bitmap Graphic System)
// ==============================================================================

object ReceiptGenerator {

    /**
     * មុខងារបង្កើតរូបភាពវិក្កយបត្រ (Graphic Rendering)
     */
    fun generateAndSaveReceipt(
        context: Context,
        cartItems: List<Product>,
        total: Double,
        discount: Double,
        saleId: Long
    ) {
        // កំណត់ទំហំក្រដាស (Width 400px x Height 600px - សម្រាប់ Thermal Printer)
        val width = 400
        val height = 700
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        // Paint Setup
        val titlePaint = Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val textPaint = Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 24f
            typeface = Typeface.DEFAULT
        }

        // --- គូរ Header ---
        canvas.drawText("MINI-MART 2500", (width / 2).toFloat(), 60f, titlePaint)
        canvas.drawText("ភ្នំពេញ, កម្ពុជា", (width / 2).toFloat(), 100f, textPaint)

        // --- ព័ត៌មានវិក្កយបត្រ ---
        val divider = "--------------------------------------------"
        canvas.drawText("លេខវិក្កយបត្រ: #$saleId", 20f, 160f, textPaint)
        canvas.drawText(divider, 20f, 190f, textPaint)

        // --- បញ្ជីទំនិញ ---
        var yPosition = 230f
        cartItems.forEach { item ->
            val rowText = "${item.name} x 1"
            val priceText = "${item.sellPrice.toInt()} ៛"
            canvas.drawText(rowText, 20f, yPosition, textPaint)
            canvas.drawText(priceText, (width - 40).toFloat(), yPosition, textPaint)
            yPosition += 40f
        }

        // --- សរុបទឹកប្រាក់ ---
        canvas.drawText(divider, 20f, yPosition + 20f, textPaint)

        val totalPaint = Paint(textPaint).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 28f }

        canvas.drawText("បញ្ចុះតម្លៃ:", 20f, yPosition + 70f, textPaint)
        canvas.drawText("- ${discount.toInt()} ៛", (width - 40).toFloat(), yPosition + 70f, textPaint)

        canvas.drawText("សរុបត្រូវបង់:", 20f, yPosition + 120f, totalPaint)
        canvas.drawText("${total.toInt()} ៛", (width - 40).toFloat(), yPosition + 120f, totalPaint)

        canvas.drawText("សូមអរគុណ!", (width / 2).toFloat(), yPosition + 180f, titlePaint)

        // រក្សាទុកទៅកាន់ Gallery
        saveBitmapToGallery(context, bitmap, "Receipt_$saleId")
    }

    /**
     * មុខងាររក្សាទុកវិក្កយបត្រទៅក្នុង Gallery
     */
    private fun saveBitmapToGallery(context: Context, bitmap: Bitmap, fileName: String) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MiniMartReceipts")
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
            Toast.makeText(context, "បានរក្សាទុកវិក្កយបត្រក្នុង Gallery!", Toast.LENGTH_SHORT).show()
        }
    }
}
// ==============================================================================
// STAB 6: ADVANCED REPORT SCREEN (Analytics Dashboard)
// ==============================================================================

@Composable
fun ReportScreen(vm: MainViewModel) {
    val scope = rememberCoroutineScope()

    // បង្ខំឱ្យផ្ទុកទិន្នន័យឡើងវិញនៅពេលចូលមកកាន់អេក្រង់នេះ
    LaunchedEffect(Unit) {
        vm.loadAllData(scope)
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        // --- 1. HEADER ---
        Text(
            text = "របាយការណ៍ និងស្ថិតិលក់",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))

        // --- 2. ANALYTICS CARDS (កាតស្ថិតិ) ---
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatCard(
                title = "ចំណូលសរុប",
                value = "${vm.sales.sumOf { it.finalAmount }.toInt()} ៛",
                color = MaterialTheme.colorScheme.primary
            )
            StatCard(
                title = "វិក្កយបត្រ",
                value = "${vm.sales.size}",
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- 3. RECENT SALES HISTORY ---
        Text(
            text = "ប្រវត្តិការលក់ថ្មីៗ",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            items(vm.sales.reversed()) { sale -> // បង្ហាញការលក់ចុងក្រោយនៅខាងលើ
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("លេខវិក្កយបត្រ: #${sale.invoiceNo}", fontWeight = FontWeight.Bold)
                            Text(
                                text = "វិធីបង់ប្រាក់: ${sale.paymentMethod}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${sale.finalAmount.toInt()} ៛",
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
// ==============================================================================
// STAB 7: NAVIGATION (បំបែកដាច់ដោយឡែក)
// ==============================================================================

@Composable
fun AppNavigation(vm: MainViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text("ដើម") },
                    selected = currentRoute == "dashboard",
                    onClick = { navController.navigate("dashboard") { popUpTo(0) } }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.ShoppingCart, null) },
                    label = { Text("លក់") },
                    selected = currentRoute == "pos",
                    onClick = { navController.navigate("pos") }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                    label = { Text("របាយការណ៍") },
                    selected = currentRoute == "report",
                    onClick = { navController.navigate("report") }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("dashboard") {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("ស្វាគមន៍មកកាន់ Mini-Mart & 2500")
                }
            }
            composable("pos") { POSScreen(vm) }
            composable("report") { ReportScreen(vm) }
        }
    }
}
// ==============================================================================
// STAB 8: MAIN ACTIVITY (The Entry Point)
// ==============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ការបង្កើត Instance របស់ Database ដោយប្រើ Singleton pattern ដែលយើងបានកំណត់ក្នុង STAB 1
        val db = MiniMartDatabase.getDatabase(applicationContext)

        // ការបង្កើត ViewModel សម្រាប់គ្រប់គ្រង State នៃ App
        val vm = MainViewModel(db)

        setContent {
            // ការកំណត់ Theme ឱ្យ App មើលទៅមានសោភ័ណភាព
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6200EE),
                    secondary = Color(0xFF03DAC6),
                    background = Color(0xFFF5F5F5)
                )
            ) {
                val scope = rememberCoroutineScope()

                // ផ្ទុកទិន្នន័យដំបូងនៅពេល App ចាប់ផ្តើម
                LaunchedEffect(Unit) {
                    vm.loadAllData(scope)
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // បញ្ជូន ViewModel ទៅកាន់ Navigation System
                    AppNavigation(vm)
                }
            }
        }
    }
}